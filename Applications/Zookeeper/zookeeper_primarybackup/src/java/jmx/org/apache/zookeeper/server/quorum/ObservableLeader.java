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

import java.io.IOException;

import org.apache.zookeeper.server.util.EventInfo;
import org.apache.zookeeper.server.util.ObserverManager;
import org.apache.zookeeper.server.util.QuorumPeerObserver;

/**
 * This observable leader implementation notifies its registered observers
 * of its important life cycle events: startup and shutdown.
 * <p>
 * In order to be able to receive leader notifications, application must 
 * implement {@link QuorumPeerObserver} and register an instance of the interface
 * with {@link ObserverManager}.
 */
public class ObservableLeader extends Leader{

    private enum Event{
        STARTUP() {
            public void dispatch(ObservableQuorumPeer peer,
                    QuorumPeerObserver ob,Leader leader) {
                ob.onLeaderStarted(peer,leader);
            }
        },
        SHUTDOWN() {
            public void dispatch(ObservableQuorumPeer peer,
                    QuorumPeerObserver ob,Leader leader) {
                ob.onLeaderShutdown(peer,leader);
            }
        };
        public abstract void dispatch(ObservableQuorumPeer peer,
                QuorumPeerObserver ob,Leader leader);
    }

    public ObservableLeader(QuorumPeer self, LeaderZooKeeperServer zk)
            throws IOException {
        super(self, zk);
    }

    static private class PeerEvent implements EventInfo{
        private Event ev;
        private Leader info;
        PeerEvent(Event ev,Leader info){
            this.ev=ev;
            this.info=info;
        }
        public void dispatch(Object source, Object ob) {
            ev.dispatch((ObservableQuorumPeer)source,(QuorumPeerObserver)ob,info);
        }
    }
    // QuorumPeer calls this method as soon as the peer has been elected a leader
    void lead() throws IOException, InterruptedException {
        try{
            ObserverManager.getInstance().notifyObservers((ObservableQuorumPeer)self, 
                    new PeerEvent(Event.STARTUP,this));
            super.lead();
        }finally{
            ObserverManager.getInstance().notifyObservers((ObservableQuorumPeer)self, 
                    new PeerEvent(Event.SHUTDOWN,this));            
        }
    }

}
