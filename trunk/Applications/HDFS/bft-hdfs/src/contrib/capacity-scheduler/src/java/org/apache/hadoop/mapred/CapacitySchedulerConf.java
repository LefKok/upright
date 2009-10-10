/** Licensed to the Apache Software Foundation (ASF) under one
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

package org.apache.hadoop.mapred;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;

/**
 * Class providing access to resource manager configuration.
 * 
 * Resource manager configuration involves setting up queues, and defining
 * various properties for the queues. These are typically read from a file 
 * called capacity-scheduler.xml that must be in the classpath of the
 * application. The class provides APIs to get/set and reload the 
 * configuration for the queues.
 */
class CapacitySchedulerConf {
  
  /** Default file name from which the resource manager configuration is read. */ 
  public static final String SCHEDULER_CONF_FILE = "capacity-scheduler.xml";
  
  private int defaultReclaimTime;
  
  private int defaultUlimitMinimum;
  
  private boolean defaultSupportPriority;
  
  private static final String QUEUE_CONF_PROPERTY_NAME_PREFIX = 
    "mapred.capacity-scheduler.queue.";

  private Configuration rmConf;
  
  /**
   * Create a new ResourceManagerConf.
   * This method reads from the default configuration file mentioned in
   * {@link RM_CONF_FILE}, that must be present in the classpath of the
   * application.
   */
  public CapacitySchedulerConf() {
    rmConf = new Configuration(false);
    rmConf.addResource(SCHEDULER_CONF_FILE);
    initializeDefaults();
  }

  /**
   * Create a new ResourceManagerConf reading the specified configuration
   * file.
   * 
   * @param configFile {@link Path} to the configuration file containing
   * the resource manager configuration.
   */
  public CapacitySchedulerConf(Path configFile) {
    rmConf = new Configuration(false);
    rmConf.addResource(configFile);
    initializeDefaults();
  }
  
  /*
   * Method used to initialize the default values and the queue list
   * which is used by the Capacity Scheduler.
   */
  private void initializeDefaults() {
    defaultReclaimTime = rmConf.getInt(
        "mapred.capacity-scheduler.default-reclaim-time-limit",300);
    defaultUlimitMinimum = rmConf.getInt(
        "mapred.capacity-scheduler.default-minimum-user-limit-percent", 100);
    defaultSupportPriority = rmConf.getBoolean(
        "mapred.capacity-scheduler.default-supports-priority", false);
  }
  
  /**
   * Get the guaranteed percentage of the cluster for the specified queue.
   * 
   * This method defaults to configured default Guaranteed Capacity if
   * no value is specified in the configuration for this queue. 
   * If the configured capacity is negative value or greater than 100 an
   * {@link IllegalArgumentException} is thrown.
   * 
   * If default Guaranteed capacity is not configured for a queue, then
   * system allocates capacity based on what is free at the time of 
   * capacity scheduler start
   * 
   * 
   * @param queue name of the queue
   * @return guaranteed percent of the cluster for the queue.
   */
  public float getGuaranteedCapacity(String queue) {
    //Check done in order to return default GC which can be negative
    //In case of both GC and default GC not configured.
    //Last check is if the configuration is specified and is marked as
    //negative we throw exception
    String raw = rmConf.getRaw(toFullPropertyName(queue, 
        "guaranteed-capacity"));
    if(raw == null) {
      return -1;
    }
    float result = rmConf.getFloat(toFullPropertyName(queue, 
                                   "guaranteed-capacity"), 
                                   -1);
    if (result < 0.0 || result > 100.0) {
      throw new IllegalArgumentException("Illegal capacity for queue " + queue +
                                         " of " + result);
    }
    return result;
  }
  
  /**
   * Sets the Guaranteed capacity of the given queue.
   * 
   * @param queue name of the queue
   * @param gc guaranteed percent of the cluster for the queue.
   */
  public void setGuaranteedCapacity(String queue,float gc) {
    rmConf.setFloat(toFullPropertyName(queue, "guaranteed-capacity"),gc);
  }
  
  
  /**
   * Get the amount of time before which redistributed resources must be
   * reclaimed for the specified queue.
   * 
   * The resource manager distributes spare capacity from a free queue
   * to ones which are in need for more resources. However, if a job 
   * submitted to the first queue requires back the resources, they must
   * be reclaimed within the specified configuration time limit.
   * 
   * This method defaults to configured default reclaim time limit if
   * no value is specified in the configuration for this queue.
   * 
   * Throws an {@link IllegalArgumentException} when invalid value is 
   * configured.
   * 
   * @param queue name of the queue
   * @return reclaim time limit for this queue.
   */
  public int getReclaimTimeLimit(String queue) {
    int reclaimTimeLimit = rmConf.getInt(toFullPropertyName(queue, "reclaim-time-limit"), 
        defaultReclaimTime);
    if(reclaimTimeLimit <= 0) {
      throw new IllegalArgumentException("Invalid reclaim time limit : " 
          + reclaimTimeLimit + " for queue : " + queue);
    }
    return reclaimTimeLimit;
  }
  
  /**
   * Set the amount of time before which redistributed resources must be
   * reclaimed for the specified queue.
   * @param queue Name of the queue
   * @param value Amount of time before which the redistributed resources
   * must be retained.
   */
  public void setReclaimTimeLimit(String queue, int value) {
    rmConf.setInt(toFullPropertyName(queue, "reclaim-time-limit"), value);
  }
  
  /**
   * Get whether priority is supported for this queue.
   * 
   * If this value is false, then job priorities will be ignored in 
   * scheduling decisions. This method defaults to <code>false</code> if 
   * the property is not configured for this queue. 
   * @param queue name of the queue
   * @return Whether this queue supports priority or not.
   */
  public boolean isPrioritySupported(String queue) {
    return rmConf.getBoolean(toFullPropertyName(queue, "supports-priority"),
        defaultSupportPriority);  
  }
  
  /**
   * Set whether priority is supported for this queue.
   * 
   * 
   * @param queue name of the queue
   * @param value true, if the queue must support priorities, false otherwise.
   */
  public void setPrioritySupported(String queue, boolean value) {
    rmConf.setBoolean(toFullPropertyName(queue, "supports-priority"), value);
  }
  
  /**
   * Get the minimum limit of resources for any user submitting jobs in 
   * this queue, in percentage.
   * 
   * This method defaults to default user limit configured if
   * no value is specified in the configuration for this queue.
   * 
   * Throws an {@link IllegalArgumentException} when invalid value is 
   * configured.
   * 
   * @param queue name of the queue
   * @return minimum limit of resources, in percentage, that will be 
   * available for a user.
   * 
   */
  public int getMinimumUserLimitPercent(String queue) {
    int userLimit = rmConf.getInt(toFullPropertyName(queue,
        "minimum-user-limit-percent"), defaultUlimitMinimum);
    if(userLimit <= 0 || userLimit > 100) {
      throw new IllegalArgumentException("Invalid user limit : "
          + userLimit + " for queue : " + queue);
    }
    return userLimit;
  }
  
  /**
   * Set the minimum limit of resources for any user submitting jobs in
   * this queue, in percentage.
   * 
   * @param queue name of the queue
   * @param value minimum limit of resources for any user submitting jobs
   * in this queue
   */
  public void setMinimumUserLimitPercent(String queue, int value) {
    rmConf.setInt(toFullPropertyName(queue, "minimum-user-limit-percent"), 
                    value);
  }
  
  /**
   * Reload configuration by clearing the information read from the 
   * underlying configuration file.
   */
  public synchronized void reloadConfiguration() {
    rmConf.reloadConfiguration();
    initializeDefaults();
  }
  
  private static final String toFullPropertyName(String queue, 
                                                  String property) {
      return QUEUE_CONF_PROPERTY_NAME_PREFIX + queue + "." + property;
  }
  
}
