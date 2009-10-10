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
package org.apache.hadoop.mapred;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.mapred.JobStatusChangeEvent.EventType;

/**
 * A {@link JobInProgressListener} that maintains the jobs being managed in
 * one or more queues. 
 */
class JobQueuesManager extends JobInProgressListener {

  /* 
   * If a queue supports priorities, waiting jobs must be 
   * sorted on priorities, and then on their start times (technically, 
   * their insertion time.  
   * If a queue doesn't support priorities, waiting jobs are
   * sorted based on their start time.  
   * Running jobs are not sorted. A job that started running earlier
   * is ahead in the queue, so insertion should be at the tail.
   */
  
  // comparator for jobs in queues that support priorities
  private static final Comparator<JobInProgress> PRIORITY_JOB_COMPARATOR
    = new Comparator<JobInProgress>() {
    public int compare(JobInProgress o1, JobInProgress o2) {
      // Look at priority.
      int res = o1.getPriority().compareTo(o2.getPriority());
      if (res == 0) {
        // the job that started earlier wins
        if (o1.getStartTime() < o2.getStartTime()) {
          res = -1;
        } else {
          res = (o1.getStartTime() == o2.getStartTime() ? 0 : 1);
        }
      }
      if (res == 0) {
        res = o1.getJobID().compareTo(o2.getJobID());
      }
      return res;
    }
  };
  // comparator for jobs in queues that don't support priorities
  private static final Comparator<JobInProgress> STARTTIME_JOB_COMPARATOR
    = new Comparator<JobInProgress>() {
    public int compare(JobInProgress o1, JobInProgress o2) {
      // the job that started earlier wins
      if (o1.getStartTime() < o2.getStartTime()) {
        return -1;
      } else {
        return (o1.getStartTime() == o2.getStartTime() ? 0 : 1);
      }
    }
  };
  
  // class to store queue info
  private static class QueueInfo {

    // whether the queue supports priorities
    boolean supportsPriorities;
    // maintain separate collections of running & waiting jobs. This we do 
    // mainly because when a new job is added, it cannot superceede a running 
    // job, even though the latter may be a lower priority. If this is ever
    // changed, we may get by with one collection. 
    Collection<JobInProgress> waitingJobs;
    Collection<JobInProgress> runningJobs;
    
    QueueInfo(boolean prio) {
      this.supportsPriorities = prio;
      if (supportsPriorities) {
        this.waitingJobs = new TreeSet<JobInProgress>(PRIORITY_JOB_COMPARATOR);
      }
      else {
        this.waitingJobs = new TreeSet<JobInProgress>(STARTTIME_JOB_COMPARATOR);
      }
      this.runningJobs = new LinkedList<JobInProgress>();
    }

    /**
     * we need to delete an object from our TreeSet based on referential
     * equality, rather than value equality that the TreeSet uses. 
     * Another way to do this is to extend the TreeSet and override remove().
     */
    static private boolean removeOb(Collection<JobInProgress> c, Object o) {
      Iterator<JobInProgress> i = c.iterator();
      while (i.hasNext()) {
          if (i.next() == o) {
              i.remove();
              return true;
          }
      }
      return false;
    }
    
  }
  
  // we maintain a hashmap of queue-names to queue info
  private Map<String, QueueInfo> jobQueues = 
    new HashMap<String, QueueInfo>();
  private static final Log LOG = LogFactory.getLog(JobQueuesManager.class);
  private CapacityTaskScheduler scheduler;

  
  JobQueuesManager(CapacityTaskScheduler s) {
    this.scheduler = s;
  }
  
  /**
   * create an empty queue with the default comparator
   * @param queueName The name of the queue
   * @param supportsPriotities whether the queue supports priorities
   */
  public void createQueue(String queueName, boolean supportsPriotities) {
    jobQueues.put(queueName, new QueueInfo(supportsPriotities));
  }
  
  /**
   * Returns the queue of running jobs associated with the name
   */
  public Collection<JobInProgress> getRunningJobQueue(String queueName) {
    return jobQueues.get(queueName).runningJobs;
  }
  
  /**
   * Returns the queue of waiting jobs associated with the name
   */
  public Collection<JobInProgress> getWaitingJobQueue(String queueName) {
    return jobQueues.get(queueName).waitingJobs;
  }
  
  @Override
  public void jobAdded(JobInProgress job) {
    LOG.info("Job submitted to queue " + job.getProfile().getQueueName());
    // add job to the right queue
    QueueInfo qi = jobQueues.get(job.getProfile().getQueueName());
    if (null == qi) {
      // job was submitted to a queue we're not aware of
      LOG.warn("Invalid queue " + job.getProfile().getQueueName() + 
          " specified for job" + job.getProfile().getJobID() + 
          ". Ignoring job.");
      return;
    }
    // add job to waiting queue. It will end up in the right place, 
    // based on priority. 
    // We use our own version of removing objects based on referential
    // equality, since the 'job' object has already been changed. 
    qi.waitingJobs.add(job);
    // let scheduler know. 
    scheduler.jobAdded(job);
  }

  private void jobCompleted(JobInProgress job, QueueInfo qi) {
    LOG.info("Job " + job.getJobID().toString() + " submitted to queue " 
             + job.getProfile().getQueueName() + " has completed");
    // job could be in running or waiting queue
    if (!qi.runningJobs.remove(job)) {
      QueueInfo.removeOb(qi.waitingJobs, job);
    }
    // let scheduler know
    scheduler.jobCompleted(job);
  }
  
  // Note that job is removed when the job completes i.e in jobUpated()
  @Override
  public void jobRemoved(JobInProgress job) {}
  
  // This is used to reposition a job in the queue. A job can get repositioned 
  // because of the change in the job priority or job start-time.
  private void reorderJobs(JobInProgress job, QueueInfo qi) {
    Collection<JobInProgress> queue = qi.waitingJobs;
    
    // Remove from the waiting queue
    if (!QueueInfo.removeOb(queue, job)) {
      queue = qi.runningJobs;
      QueueInfo.removeOb(queue, job);
    }
    
    // Add back to the queue
    queue.add(job);
  }
  
  // This is used to move a job from the waiting queue to the running queue.
  private void makeJobRunning(JobInProgress job, QueueInfo qi) {
    // Remove from the waiting queue
    QueueInfo.removeOb(qi.waitingJobs, job);
    
    // Add the job to the running queue
    qi.runningJobs.add(job);
  }
  
  // Update the scheduler as job's state has changed
  private void jobStateChanged(JobStatusChangeEvent event, QueueInfo qi) {
    JobInProgress job = event.getJobInProgress();
    // Check if the ordering of the job has changed
    // For now priority and start-time can change the job ordering
    if (event.getEventType() == EventType.PRIORITY_CHANGED 
        || event.getEventType() == EventType.START_TIME_CHANGED) {
      // Make a priority change
      reorderJobs(job, qi);
    } else if (event.getEventType() == EventType.RUN_STATE_CHANGED) {
      // Check if the job is complete
      int runState = job.getStatus().getRunState();
      if (runState == JobStatus.SUCCEEDED
          || runState == JobStatus.FAILED
          || runState == JobStatus.KILLED) {
        jobCompleted(job, qi);
      } else if (runState == JobStatus.RUNNING) {
        makeJobRunning(job, qi);
      }
    }
  }
  
  @Override
  public void jobUpdated(JobChangeEvent event) {
    JobInProgress job = event.getJobInProgress();
    QueueInfo qi = jobQueues.get(job.getProfile().getQueueName());
    if (null == qi) {
      // can't find queue for job. Shouldn't happen. 
      LOG.warn("Could not find queue " + job.getProfile().getQueueName() + 
          " when updating job " + job.getProfile().getJobID());
      return;
    }
    
    // Check if this is the status change
    if (event instanceof JobStatusChangeEvent) {
      jobStateChanged((JobStatusChangeEvent)event, qi);
    }
  }
  
}
