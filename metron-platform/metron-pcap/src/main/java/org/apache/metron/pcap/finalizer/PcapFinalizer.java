/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.metron.pcap.finalizer;

import static java.lang.String.format;
import static org.apache.metron.pcap.config.PcapGlobalDefaults.NUM_RECORDS_PER_FILE_DEFAULT;

import com.google.common.collect.Iterables;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.metron.common.hadoop.SequenceFileIterable;
import org.apache.metron.job.Finalizer;
import org.apache.metron.job.JobException;
import org.apache.metron.job.Pageable;
import org.apache.metron.pcap.PcapPages;
import org.apache.metron.pcap.config.PcapOptions;
import org.apache.metron.pcap.writer.PcapResultsWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes Pcap results from a specified path - for PCAP, it is assumed that these results are SequenceFileIterables.
 * The results are then processed by partitioning the results based on a num records per file option
 * into a final output file with a PCAP header for each partition, and written to a final output location.
 * The MapReduce results are cleaned up after successfully writing out the final results.
 */
public abstract class PcapFinalizer implements Finalizer<Path> {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private PcapResultsWriter resultsWriter;

  protected PcapFinalizer() {
    this.resultsWriter = new PcapResultsWriter();
  }

  protected PcapResultsWriter getResultsWriter() {
    return resultsWriter;
  }

  @Override
  public Pageable<Path> finalizeJob(Map<String, Object> config) throws JobException {
    Configuration hadoopConfig = PcapOptions.HADOOP_CONF.get(config, Configuration.class);
    int recPerFile = PcapOptions.NUM_RECORDS_PER_FILE
        .getOrDefault(config, Integer.class, NUM_RECORDS_PER_FILE_DEFAULT);
    Path interimResultPath = PcapOptions.INTERIM_RESULT_PATH
        .get(config, PcapOptions.STRING_TO_PATH, Path.class);
    FileSystem fs = PcapOptions.FILESYSTEM.get(config, FileSystem.class);
    int parallelism = getNumThreads(PcapOptions.FINALIZER_THREADPOOL_SIZE.get(config, String.class));
    LOG.info("Finalizer running with parallelism set to " + parallelism);

    SequenceFileIterable interimResults = null;
    try {
      interimResults = readInterimResults(interimResultPath, hadoopConfig, fs);
    } catch (IOException e) {
      throw new JobException("Unable to read interim job results while finalizing", e);
    }
    List<Path> outFiles = new ArrayList<>();
    try {
      Iterable<List<byte[]>> partitions = Iterables.partition(interimResults, recPerFile);
      Map<Path, List<byte[]>> toWrite = new HashMap<>();
      int part = 1;
      if (partitions.iterator().hasNext()) {
        for (List<byte[]> data : partitions) {
          Path outputPath = getOutputPath(config, part++);
          toWrite.put(outputPath, data);
        }
        outFiles = writeParallel(hadoopConfig, toWrite, parallelism);
      } else {
        LOG.info("No results returned.");
      }
    } catch (IOException e) {
      throw new JobException("Failed to finalize results", e);
    } finally {
      try {
        interimResults.cleanup();
      } catch (IOException e) {
        LOG.warn("Unable to cleanup files in HDFS", e);
      }
    }
    LOG.info("Done finalizing results");
    return new PcapPages(outFiles);
  }

  /**
   * Figure out how many threads to use in the thread pool. If it's a string and ends with "C",
   * then strip the C and treat it as an integral multiple of the number of cores.  If it's a
   * string and does not end with a C, then treat it as a number in string form.
   */
  private static int getNumThreads(String numThreads) throws JobException {
    String numThreadsStr = ((String) numThreads).trim().toUpperCase();
    try {
      if (numThreadsStr.endsWith("C")) {
        Integer factor = Integer.parseInt(numThreadsStr.replace("C", ""));
        return factor * Runtime.getRuntime().availableProcessors();
      } else {
        return Integer.parseInt(numThreadsStr);
      }
    } catch (NumberFormatException e) {
      throw new JobException(
          format("Unable to set number of threads for finalizing from property value '%s'",
              numThreads));
    }
  }

  protected List<Path> writeParallel(Configuration hadoopConfig, Map<Path, List<byte[]>> toWrite,
      int parallelism) throws IOException {
    List<Path> outFiles = Collections.synchronizedList(new ArrayList<>());
    ForkJoinPool tp = new ForkJoinPool(parallelism);
    try {
      tp.submit(() -> {
        toWrite.entrySet().parallelStream().forEach(e -> {
          Path path = e.getKey();
          List<byte[]> data = e.getValue();
          if (data.size() > 0) {
            try {
              write(getResultsWriter(), hadoopConfig, data, path);
            } catch (IOException ioe) {
              throw new RuntimeException(
                  String.format("Failed to write results to path '%s'", path.toString()), ioe);
            }
            outFiles.add(path);
          }
        });
      }).get();
    } catch (InterruptedException | ExecutionException  e) {
      throw new IOException("Error finalizing results.", e);
    } catch (RuntimeException e) {
      throw new IOException(e.getMessage(), e.getCause());
    }
    outFiles.sort((o1, o2) -> o1.getName().compareTo(o2.getName()));
    return outFiles;
  }

  protected abstract void write(PcapResultsWriter resultsWriter, Configuration hadoopConfig,
      List<byte[]> data, Path outputPath) throws IOException;

  protected abstract Path getOutputPath(Map<String, Object> config, int partition);

  /**
   * Returns a lazily-read Iterable over a set of sequence files.
   */
  protected SequenceFileIterable readInterimResults(Path interimResultPath, Configuration config,
      FileSystem fs) throws IOException {
    List<Path> files = new ArrayList<>();
    for (RemoteIterator<LocatedFileStatus> it = fs.listFiles(interimResultPath, false);
        it.hasNext(); ) {
      Path p = it.next().getPath();
      if (p.getName().equals("_SUCCESS")) {
        fs.delete(p, false);
        continue;
      }
      files.add(p);
    }
    if (files.size() == 0) {
      LOG.info("No files to process with specified date range.");
    } else {
      LOG.debug("Interim results path={}", interimResultPath);
      Collections.sort(files, (o1, o2) -> o1.getName().compareTo(o2.getName()));
    }
    return new SequenceFileIterable(files, config);
  }
}
