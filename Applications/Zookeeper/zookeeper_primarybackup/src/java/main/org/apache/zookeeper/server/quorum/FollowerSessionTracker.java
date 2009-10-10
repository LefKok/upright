// $Id$

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

package org.apache.zookeeper.server.quorum;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.zookeeper.server.SessionInfo;
import org.apache.zookeeper.server.SessionTracker;
import org.apache.zookeeper.server.SessionTrackerImpl;

/**
 * This is really just a shell of a SessionTracker that tracks session activity
 * to be forwarded to the Leader using a PING.
 */
public class FollowerSessionTracker implements SessionTracker {
    SessionExpirer expirer;

    HashMap<Long, Integer> touchTable = new HashMap<Long, Integer>();
    long serverId = 1;
    long nextSessionId=0;
    
    private ConcurrentHashMap<Long, SessionInfo> sessionsWithTimeouts;

    
    /**
     * 
     */
    public FollowerSessionTracker(SessionExpirer expirer,
            ConcurrentHashMap<Long, SessionInfo> sessionsWithTimeouts, long id, long timestamp) {
        this.expirer = expirer;
        this.sessionsWithTimeouts = sessionsWithTimeouts;
        this.serverId = id;
        nextSessionId = SessionTrackerImpl.initializeNextSession(this.serverId, timestamp);
        
    }

    synchronized public void removeSession(long sessionId) {
        sessionsWithTimeouts.remove(sessionId);
        touchTable.remove(sessionId);
    }

    public void shutdown() {
    }

    synchronized public void addSession(long sessionId, int sessionTimeout, long ttt, long timestamp) {
        sessionsWithTimeouts.put(sessionId, new SessionInfo(sessionTimeout,0));
        touchTable.put(sessionId, sessionTimeout);
    }

    synchronized public boolean touchSession(long sessionId, int sessionTimeout, long timestamp) {
        touchTable.put(sessionId, sessionTimeout);
        return true;
    }

    synchronized HashMap<Long, Integer> snapshot() {
        HashMap<Long, Integer> oldTouchTable = touchTable;
        touchTable = new HashMap<Long, Integer>();
        return oldTouchTable;
    }


    synchronized public long createSession(int sessionTimeout, long timestamp) {
        return (nextSessionId++);
    }

    public void checkSession(long sessionId)  {
        // Nothing to do here. Sessions are checked at the Leader
    }
}
