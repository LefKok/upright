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

package org.apache.hadoop.mapreduce.lib.output;

import java.io.IOException;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.conf.Configuration;

/** An {@link OutputFormat} that writes {@link SequenceFile}s. */
public class SequenceFileOutputFormat <K,V> extends FileOutputFormat<K, V> {

  public RecordWriter<K, V> getRecordWriter(TaskAttemptContext context)
    throws IOException {
    // get the path of the temporary output file 
    Path file = FileOutputFormat.getTaskOutputPath(context);
    Configuration conf = context.getConfiguration();
    
    FileSystem fs = file.getFileSystem(conf);
    CompressionCodec codec = null;
    CompressionType compressionType = CompressionType.NONE;
    if (getCompressOutput(conf)) {
      // find the kind of compression to do
      compressionType = getOutputCompressionType(conf);

      // find the right codec
      Class<?> codecClass = getOutputCompressorClass(conf, DefaultCodec.class);
      codec = (CompressionCodec) 
        ReflectionUtils.newInstance(codecClass, conf);
    }
    final SequenceFile.Writer out = 
      SequenceFile.createWriter(fs, conf, file,
                                context.getOutputKeyClass(),
                                context.getOutputValueClass(),
                                compressionType,
                                codec,
                                context);

    return new RecordWriter<K, V>() {

        public void write(K key, V value)
          throws IOException {

          out.append(key, value);
        }

        public void close(TaskAttemptContext context) throws IOException { 
          out.close();
        }
      };
  }

  /**
   * Get the {@link CompressionType} for the output {@link SequenceFile}.
   * @param conf the {@link Configuration}
   * @return the {@link CompressionType} for the output {@link SequenceFile}, 
   *         defaulting to {@link CompressionType#RECORD}
   */
  public static CompressionType getOutputCompressionType(Configuration conf) {
    String val = conf.get("mapred.output.compression.type", 
                          CompressionType.RECORD.toString());
    return CompressionType.valueOf(val);
  }
  
  /**
   * Set the {@link CompressionType} for the output {@link SequenceFile}.
   * @param conf the {@link Configuration} to modify
   * @param style the {@link CompressionType} for the output
   *              {@link SequenceFile} 
   */
  public static void setOutputCompressionType(Configuration conf, 
		                                          CompressionType style) {
    setCompressOutput(conf, true);
    conf.set("mapred.output.compression.type", style.toString());
  }

}

