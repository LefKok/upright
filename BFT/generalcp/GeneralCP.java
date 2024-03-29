package BFT.generalcp;

import BFT.Debug;
import BFT.messages.CommandBatch;
import BFT.messages.NonDeterminism;
import BFT.network.concurrentNet.*;
import BFT.serverShim.GlueShimInterface;
import BFT.serverShim.ServerShimInterface;
import BFT.serverShim.ShimBaseNode;
import BFT.serverShim.Worker;
import BFT.util.Role;

import java.io.*;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Random;
import java.net.InetAddress;

/**
 * Created by IntelliJ IDEA. User: Yang Wang Date: 2009-3-28 Time: 14:05:09 To
 * change this template use File | Settings | File Templates.
 */
public class GeneralCP implements GlueShimInterface, CPAppInterface {

	private ShimBaseNode shim;
	private AppCPInterface app;
	private GeneralCPLogger logger;

	private HashThread hashThread;
	private LoadStateThread loadThread;
	private ExecThread execThread;
	private SharedState state = new SharedState();

	private ArrayList<Thread> threads = new ArrayList<Thread>();

	private static String SNAPSHOT_FILE_PREFIX = null;
	private static String LOG_FILE_PREFIX = null;
	
	public static int APP_CP_INTERVAL = 10000;
	private long lastSeqNoExecuted = -1;

	public GeneralCP(int id, String membership, String logPath,
			String snapshotPath) {
		shim = new ShimBaseNode(membership, id, new byte[0]);
		((ShimBaseNode) shim).setGlue(this);
		this.LOG_FILE_PREFIX = logPath + File.separator;

		this.SNAPSHOT_FILE_PREFIX = snapshotPath + File.separator;
	}

	private long startTime = -1;

	public void setupApplication(AppCPInterface app) {
		startTime = System.currentTimeMillis();
		System.out.println("StartTime=" + startTime);
		this.app = app;
		this.logger = new GeneralCPLogger(LOG_FILE_PREFIX, state);
		this.hashThread = new HashThread(shim, state);
		this.hashThread.start();
		this.loadThread = new LoadStateThread();
		this.loadThread.start();
		this.execThread = new ExecThread();
		this.execThread.start();

		// start shim here
		/*NetworkWorkQueue nwq = new NetworkWorkQueue();
		TCPNetwork net = new TCPNetwork(Role.ORDER, ((ShimBaseNode) shim)
				.getMembership());
		((ShimBaseNode) shim).setNetwork(net);
		net.start();
		Listener lo = new Listener(net, nwq);
		net = new TCPNetwork(Role.CLIENT, ((ShimBaseNode) shim).getMembership());
		((ShimBaseNode) shim).setNetwork(net);
		net.start();
		Listener lc = new Listener(net, nwq);
		net = new TCPNetwork(Role.FILTER, ((ShimBaseNode) shim).getMembership());
		((ShimBaseNode) shim).setNetwork(net);
		net.start();
		Listener lf = new Listener(net, nwq);
		net = new TCPNetwork(Role.EXEC, ((ShimBaseNode) shim).getMembership());
		((ShimBaseNode) shim).setNetwork(net);
		net.start();
		Listener le = new Listener(net, nwq);
		Thread ltc = new Thread(lc);
		Thread lto = new Thread(lo);
		Thread lte = new Thread(le);
		Thread ltf = new Thread(lf);
		Worker w = new Worker(nwq, ((ShimBaseNode) shim));
		Thread wt = new Thread(w);
		threads.add(ltc);
		threads.add(lto);
		threads.add(lte);
		threads.add(ltf);
		threads.add(wt);
		((ShimBaseNode) shim).start();
		wt.start();
		lte.start();
		lto.start();
		ltc.start();
		ltf.start();*/
		NetworkWorkQueue nwq = new NetworkWorkQueue();
                NettyTCPNetwork orderNet = new NettyTCPNetwork(Role.ORDER, shim.getMembership(), nwq);
                shim.setNetwork(orderNet);
                NettyTCPNetwork clientNet = new NettyTCPNetwork(Role.CLIENT, shim.getMembership(), nwq);
                shim.setNetwork(clientNet);
                NettyTCPNetwork execNet = new NettyTCPNetwork(Role.EXEC, shim.getMembership(), nwq);
                shim.setNetwork(execNet);
                NettyTCPNetwork filterNet = new NettyTCPNetwork(Role.FILTER, shim.getMembership(), nwq);
                shim.setNetwork(filterNet);


                BFT.serverShim.Worker w = new BFT.serverShim.Worker(nwq, shim);
                Thread wt = new Thread(w);
                shim.start();
                wt.start();
                orderNet.start();
                clientNet.start();
                execNet.start();
                filterNet.start();

		new FetchStateThread().start();
	}

	public void stop() {
		for (Thread t : threads) {
			t.interrupt();
		}
		threads.clear();
	}

	public void exec(CommandBatch batch, long seqNo, NonDeterminism nd,
			boolean takeCP) {
		try {
			// System.out.println("GeneralCP exec " + seqNo + " time="
			// + nd.getTime());
			// System.out.println("GeneralCP Put " + seqNo
			// + " into cache, time=" + nd.getTime());
			if (this.reqCache.size() > APP_CP_INTERVAL / 2) {
				System.out.println("Call noMoreRequests");
				shim.noMoreRequests();
			} else {
				this.reqCache.put(new BatchInfo(batch, seqNo, nd, takeCP));
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private class ExecThread extends Thread {

		private boolean isRunning = true;
		
		public ExecThread(){
			System.out.println("ExecThread created "+this);
		}

		public void terminate() {
			synchronized(this){
				isRunning = false;
				System.out.println("Terminated isRunning="+isRunning+" "+this);
			}
		}

		public void run() {
			while (true) {
				try {
					synchronized(this){
						if(isRunning==false)
							return;
					}
					Object obj = reqCache.take();
					if (obj instanceof BatchInfo) {
						BatchInfo request = (BatchInfo) obj;
						logger.addLog(request);
						CommandBatch batch = request.getBatch();
						NonDeterminism nd = request.getTime();
						long seqNo = request.getSeqNo();
						boolean takeCP = request.getTakeCP();
						//System.out.println("GeneralCP exec " + seqNo);
						BFT.messages.Entry[] entries = batch.getEntries();
						Random rand = new Random(nd.getSeed());
						for (int i = 0; i < entries.length; i++) {
							BFT.messages.Entry e = entries[i];
							int client = (int) e.getClient();
							RequestInfo info = new RequestInfo(false, client,
									seqNo, e.getRequestId(), nd.getTime(), rand
											.nextLong());
							if (takeCP && i == entries.length - 1) {
								info.setLastReqBeforeCP();
							}
							app.execAsync(e.getCommand(), info);
						}
						if (takeCP) {
							logger.flush();
						}
						if (seqNo % APP_CP_INTERVAL == APP_CP_INTERVAL - 1) {
							state.startSync(seqNo);
							app.sync();
						}
						lastSeqNoExecuted = seqNo;
					} else {
						ReadonlyRequest request = (ReadonlyRequest) obj;
						app.execReadonly(request.request, request.clientId,
								request.requestId);
					}
				} catch (InterruptedException e) {
					//e.printStackTrace();
					return;
				}
			}
		}
	}

	public class ReadonlyRequest {
		public int clientId;
		public long requestId;
		public byte[] request;

		public ReadonlyRequest(int clientId, long requestId, byte[] request) {
			this.clientId = clientId;
			this.requestId = requestId;
			this.request = request;
		}
	}

	public void execReadOnly(int clientId, long requestId, byte[] request) {
		// To change body of implemented methods use File | Settings | File
		// Templates.
		try {
			if (!inLoadCP && reqCache.size() <= APP_CP_INTERVAL / 2) {
				reqCache.put(new ReadonlyRequest(clientId, requestId, request));
			}
			//app.execReadonly(request, clientId, requestId);
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	// Private information used in LoadCP process
	private LinkedBlockingQueue<Object> reqCache = new LinkedBlockingQueue<Object>();
	private Hashtable<StateToken, Boolean> snapshotTokensToFetch = new Hashtable<StateToken, Boolean>();
	private Hashtable<StateToken, Boolean> logTokensToFetch = new Hashtable<StateToken, Boolean>();
	private ArrayList<StateToken> logsToLoad = new ArrayList<StateToken>();
	private int snapStatesFetched;
	private CPToken loadCPToken = null;
	private boolean inLoadCP = false;

	public void loadCP(byte[] cpToken, long seqNo) {
		try {
			this.inLoadCP = true;
			ByteArrayInputStream bis = new ByteArrayInputStream(cpToken);
			loadCPToken = new CPToken();
			loadCPToken.readBytes(bis);
			System.out.println("LoadCP " + seqNo + " logNumber="
					+ loadCPToken.getLogTokenSize());
			System.out.println(loadCPToken);
			if (state.getLastCPToken() != null)
				System.out.println("currentLogNumber="
						+ state.getLastCPToken().getLogTokenSize());
			while (loadStateQueue.size() != 0) {
				Thread.sleep(100);
			}
			loadThread.interrupt();
			loadThread = new LoadStateThread();
			loadThread.start();
			
			execThread.terminate();
			Thread.sleep(10);
			execThread.interrupt();
			reqCache.clear();
			logger.clear();

			boolean needFullLoad = false;
			int logIndexInNew = -1;
			if (logsToLoad.size() >= 100) {
				System.out.println("Too many pending logs. Need Full load");
				needFullLoad = true;
			} else {
				StateToken lastLogToken = null;
				if (logsToLoad.size() > 0) {
					lastLogToken = logsToLoad.get(logsToLoad.size() - 1);
				} else if (state.getLastCPToken().getLogTokenSize() > 0) {
					lastLogToken = state.getLastCPToken().getLogToken(
							state.getLastCPToken().getLogTokenSize() - 1);
				}
				if (lastLogToken != null) {
					for (int i = 0; i < loadCPToken.getLogTokenSize(); i++) {
						if (loadCPToken.getLogToken(i).equals(
								lastLogToken)) {
							logIndexInNew = i;
							break;
						}
					}
					if (logIndexInNew == -1)
						needFullLoad = true;
					if (logIndexInNew == loadCPToken.getLogTokenSize() - 1)
						throw new RuntimeException("The same token as before");
				} else {
					if (state.getLastCPToken().getCPSeqNo() != loadCPToken
							.getCPSeqNo())
						needFullLoad = true;
					else
						logIndexInNew = -1;
				}

				if (needFullLoad) {
					System.out.println("NeedFullLoad");
					System.out.println("Old token:" + state.getLastCPToken());
					System.out.println("New token:" + loadCPToken);
				} else {
					System.out.println("Continuous Load");
					if (loadCPToken.getCPSeqNo() != state.getLastCPToken()
							.getCPSeqNo())
						System.out.println("Cross APP_CP Load");
				}
			}
			// clear the current states

			// if(execThread.isAlive())

			// execThread.interrupt();

			ArrayList<StateToken> newLogsToLoad = new ArrayList<StateToken>();
			// check what need to be fetched
			if (needFullLoad) {
				// In this case, we need to load full checkpoint
				// Then we do not need the previous log info any more
				snapshotTokensToFetch.clear();
				snapStatesFetched = 0;
				logTokensToFetch.clear();
				synchronized(logsToLoad){
					logsToLoad.clear();
				}

				state.clear();
				for (StateToken snapToken : loadCPToken.getAppCPTokens()) {
					snapshotTokensToFetch.put(snapToken, false);
				}
				for (int i=0;i<loadCPToken.getLogTokenSize();i++){
					StateToken logToken = loadCPToken.getLogToken(i);
					logTokensToFetch.put(logToken, false);
					newLogsToLoad.add(logToken);
				}
			} else {
				for (int i = logIndexInNew + 1; i < loadCPToken.getLogTokenSize(); i++) {
					logTokensToFetch.put(loadCPToken.getLogToken(i),
							false);
					newLogsToLoad.add(loadCPToken.getLogToken(i));
				}

			}

			if (logTokensToFetch.size() + snapshotTokensToFetch.size() == 0) {
				System.out.println("No need to load CP");
				this.inLoadCP = false;
				return;
			} else {
				this.inLoadCP = true;

				// state.reset(Token.getLastSeqNo());
				// state.setLastCPToken(loadCPToken);

			}

			// Fetch the necessary states
			Hashtable<StateToken, Boolean> tmp = (Hashtable<StateToken, Boolean>) snapshotTokensToFetch
					.clone();
			for (StateToken snapToken : tmp.keySet()) {
				if (checkLocalFile(snapToken) == false) {
					shim.requestState(snapToken.getBytes());
					System.out.println("requestToken:" + snapToken);
				} else
					processStateToken(snapToken);
			}
			if (snapshotTokensToFetch.size() == 0) {
				// No need to load app cp
				snapStatesFetched = -1;
			}
			ArrayList<StateToken> tmp2 = new ArrayList<StateToken>(
					newLogsToLoad);
			for (StateToken logToken : tmp2) {
				if (checkLocalFile(logToken) == false) {
					System.out.println("requestToken:" + logToken);
					shim.requestState(logToken.getBytes());
				} else
					processStateToken(logToken);
			}
			synchronized(logsToLoad){
				logsToLoad.addAll(newLogsToLoad);
			}
			this.tryLoad();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private boolean checkLocalFile(StateToken token) {
		try {
			File file = null;
			if (token.getType() == StateToken.SNAPSHOT)
				file = new File(SNAPSHOT_FILE_PREFIX + token.getFileName());
			else
				file = new File(LOG_FILE_PREFIX + token.getFileName());
			if (!file.exists()) {
				// System.out.println(file.getAbsolutePath()
				// + " not found on local disk");
				return false;
			}
			FileInputStream fis = new FileInputStream(file);
			byte[] data = new byte[token.getLength()];
			fis.skip(token.getOffset());
			int ret = fis.read(data);
			fis.close();
			if (ret == token.getLength())
				return token.validate(data);
			else
				return false;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	private ArrayList<CPToken> releaseList = new ArrayList<CPToken>();

	public void releaseCP(byte[] cpToken) {

		try {
			if (cpToken == null || cpToken.length == 0)
				return;
			ByteArrayInputStream bis = new ByteArrayInputStream(cpToken);
			CPToken token = new CPToken();
			token.readBytes(bis);
			System.out.println("ReleaseCP " + token.getLastSeqNo());
			int logsNoPerAppCP = APP_CP_INTERVAL
					/ BFT.order.Parameters.checkPointInterval;
			if (token.getLogTokenSize() == 2 * logsNoPerAppCP) {
				releaseList.add(token);
			}
			if (releaseList.size() == 3) {
				token = releaseList.get(0);
				releaseList.remove(0);
			} else
				return;
			// System.out.println("releaseCP: "+token);
			// System.out.println("lastToken: "+state.getLastCPToken());
			if (token.getCPSeqNo() < state.getLastCPToken().getCPSeqNo()
					&& token.getLogTokenSize() == 2 * logsNoPerAppCP) {
				if (token.getAppCPTokens().size() > 0) {
					File snapFile = new File(this.SNAPSHOT_FILE_PREFIX
							+ token.getAppCPTokens().get(0).getFileName());
					if (snapFile.exists()) {
						snapFile.delete();
						System.out.println(this.SNAPSHOT_FILE_PREFIX
								+ token.getAppCPTokens().get(0).getFileName()
								+ " deleted");
					} else
						System.out.println(this.SNAPSHOT_FILE_PREFIX
								+ token.getAppCPTokens().get(0).getFileName()
								+ " not found");
				}
				if (state.getLastCPToken().getLogTokenSize() > 0) {
					long firstLogSeqNo = state.getLastCPToken().getLogToken(0)
							.getSeqNo();
					for (int i=0;i<state.getLastCPToken().getLogTokenSize();i++){
						StateToken logToken = state.getLastCPToken().getLogToken(i);
						if (logToken.getSeqNo() < firstLogSeqNo) {
							File logFile = new File(this.LOG_FILE_PREFIX
									+ logToken.getFileName());
							if (logFile.exists()) {
								logFile.delete();
								System.out.println(this.LOG_FILE_PREFIX
										+ logToken.getFileName() + " deleted");
							} else
								System.out
										.println(this.LOG_FILE_PREFIX
												+ logToken.getFileName()
												+ " not found");
						} else
							break;

					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private LinkedBlockingQueue<byte[]> fetchStateQueue = new LinkedBlockingQueue<byte[]>();

	public class FetchStateThread extends Thread {
		public void run() {
			while (true) {
				try {
					byte[] stateToken = fetchStateQueue.take();
					ByteArrayInputStream bis = new ByteArrayInputStream(
							stateToken);
					StateToken token = new StateToken();
					token.readBytes(bis);
					System.out.println("Start FetchState " + token + " time:"
							+ System.currentTimeMillis());
					File file = null;
					if (token.getType() == StateToken.SNAPSHOT)
						file = new File(SNAPSHOT_FILE_PREFIX
								+ token.getFileName());
					else
						file = new File(LOG_FILE_PREFIX + token.getFileName());
					// System.out.println("Trying file " +
					// file.getAbsolutePath());
					if (!file.exists()) {
						System.out.println(file.getAbsolutePath()
								+ " not exists");
						continue;
					}
					FileInputStream fis = new FileInputStream(file);
					byte[] data = new byte[token.getLength()];
					fis.skip(token.getOffset());
					int ret = fis.read(data);
					fis.close();
					if (ret == token.getLength())
						shim.returnState(stateToken, data);
					else
						System.out
								.println("This glue does not have enough data for "
										+ token);
					System.out.println("End FetchState " + token + " time:"
							+ System.currentTimeMillis());
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	public void fetchState(byte[] stateToken) {
		try {
			fetchStateQueue.put(stateToken);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/*
	 * public void replayCache() { System.out.println("Replay cache"); if
	 * (this.inLoadCP) throw new RuntimeException("Cannot reply cache while
	 * loading CP"); if (reqCache.size() > 0) { System.out.println("Cache
	 * start:" + reqCache.get(0).getSeqNo()); System.out.println("Cache end :" +
	 * reqCache.get(reqCache.size() - 1).getSeqNo()); long firstSeqNo =
	 * reqCache.get(0).getSeqNo(); if (firstSeqNo !=
	 * state.getLastCPToken().getLastSeqNo() + 1) throw new
	 * RuntimeException("Unmatch replay cache: seqNo=" + firstSeqNo + " after
	 * recovery"); } for (BatchInfo batch : reqCache) {
	 * this.exec(batch.getBatch(), batch.getSeqNo(), batch.getTime(),
	 * batch.getTakeCP()); } reqCache.clear(); System.out.println("End replay
	 * cache"); }
	 */

	private class LoadStateReq {
		public byte[] stateToken;
		public byte[] data;

		public LoadStateReq(byte[] stateToken, byte[] data) {
			this.stateToken = stateToken;
			this.data = data;
		}
	}

	private LinkedBlockingQueue<LoadStateReq> loadStateQueue = new LinkedBlockingQueue<LoadStateReq>();

	public void loadState(byte[] stateToken, byte[] data) {
		try {
			loadStateQueue.put(new LoadStateReq(stateToken, data));
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(-1);
		}
	}

	private class LoadStateThread extends Thread {

		public void run() {
			while (true) {
				try {
					LoadStateReq req = loadStateQueue.take();
					doLoadState(req.stateToken, req.data);
				} catch (InterruptedException e) {
					// e.printStackTrace();
					break;
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	public void doLoadState(byte[] stateToken, byte[] data) {
		try {
			ByteArrayInputStream bis = new ByteArrayInputStream(stateToken);
			StateToken token = new StateToken();
			token.readBytes(bis);
			System.out.println("Start LoadState " + token + " time:"
					+ System.currentTimeMillis());
			if (token.validate(data) == false) {
				System.out.println("Validation failed for " + token);
				return;
				// throw new RuntimeException("Validation failed");
				// later we may fetch state again
			}
			if (!this.processStateToken(token))
				return;
			System.out.println("Write to file");
			RandomAccessFile file;
			if (token.type == StateToken.SNAPSHOT) {
				file = new RandomAccessFile(SNAPSHOT_FILE_PREFIX
						+ token.getFileName(), "rw");
			} else {
				file = new RandomAccessFile(LOG_FILE_PREFIX
						+ token.getFileName(), "rw");
				file.setLength(0);
			}
			file.seek(token.getOffset());
			file.write(data);
			file.close();
			this.tryLoad();
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		} finally {
			System.out.println("End LoadState " + " time:"
					+ System.currentTimeMillis());
		}
	}

	// Update loadcp data structure. Return true if this is a new and necessary
	// token. False otherwise.
	private boolean processStateToken(StateToken token) {
		if (token.type == StateToken.SNAPSHOT) {
			if (!this.snapshotTokensToFetch.containsKey(token)) {
				return false;
				// throw new RuntimeException("Unknown token " + token);
			}
			if (this.snapshotTokensToFetch.get(token).equals(true)) {
				return false;
				// throw new RuntimeException("Duplicate token" + token);
			}
			this.snapshotTokensToFetch.remove(token);
			this.snapshotTokensToFetch.put(token, true);
			this.snapStatesFetched++;
		} else {
			if (!this.logTokensToFetch.containsKey(token)) {
				return false;
				// throw new RuntimeException("Unknown token " + token);
			}
			if (this.logTokensToFetch.get(token).equals(true)) {
				return false;
				// throw new RuntimeException("Duplicate token" + token);
			}
			this.logTokensToFetch.remove(token);
			this.logTokensToFetch.put(token, true);
		}
		return true;
	}

	// Try to load snapshot or logs or replay cache if possible
	private void tryLoad() throws IOException, InterruptedException {
		if (snapStatesFetched == snapshotTokensToFetch.size()
				&& snapStatesFetched != 0) {
			System.out.println("Snapshot file finished, load it.");

			String snapLoad = snapshotTokensToFetch.keys().nextElement()
					.getFileName();
			RandomAccessFile tmp = new RandomAccessFile(SNAPSHOT_FILE_PREFIX
					+ snapLoad, "rw");
			tmp.setLength(loadCPToken.getCPFileSize());

			//state.startLoadSnapshot();
			app.loadSnapshot(SNAPSHOT_FILE_PREFIX + snapLoad);
			//state.waitForLoadSnapshot();

			CPToken newToken = new CPToken((ArrayList<StateToken>) loadCPToken
					.getAppCPTokens().clone(), null);
			newToken.setCPSeqNo(loadCPToken.getCPSeqNo());
			this.state.setLastCPToken(newToken);
			this.state.reset(loadCPToken.getCPSeqNo());
			System.out.println("Add AppCP " + loadCPToken.getCPSeqNo()
					+ " to lastCPToken");

			snapStatesFetched = -1;
			if (this.logsToLoad.size() == 0) {
				this.inLoadCP = false;
				this.execThread = new ExecThread();
				this.execThread.start();
				System.out.println("Call readForRequests");
				shim.readyForRequests();
				System.out.println("RecoveryTime="
						+ (System.currentTimeMillis() - startTime));
			}
		}
		if (snapStatesFetched == -1 && logsToLoad.size() > 0) {
			/*
			 * for(StateToken tmp:snapshotTokensToFetch.keySet()){
			 * System.out.println ("Already got "+tmp+"
			 * "+snapshotTokensToFetch.get(tmp)); }
			 */
			synchronized(logsToLoad){
			Iterator<StateToken> iter = logsToLoad.iterator();
			StateToken toLoad = null;
			while (iter.hasNext()) {
				toLoad = iter.next();
				if (logTokensToFetch.containsKey(toLoad)
						&& logTokensToFetch.get(toLoad) == true) {
					System.out.println("LoadLog:" + toLoad.getFileName());
					ArrayList<BatchInfo> logs = this.logger.readLog(toLoad
							.getFileName());
					for (BatchInfo request : logs) {
						if (request.getSeqNo() > lastSeqNoExecuted) {
							CommandBatch batch = request.getBatch();
							NonDeterminism nd = request.getTime();
							long seqNo = request.getSeqNo();
							boolean takeCP = request.getTakeCP();
							Debug.println("GeneralCP replay " + seqNo);
							BFT.messages.Entry[] entries = batch.getEntries();
							Random rand = new Random(nd.getSeed());
							for (int i = 0; i < entries.length; i++) {
								BFT.messages.Entry e = entries[i];
								int client = (int) e.getClient();
								RequestInfo info = new RequestInfo(false,
										client, seqNo, e.getRequestId(), nd
												.getTime(), rand.nextLong());
								if (takeCP && i == entries.length - 1) {
									info.setLastReqBeforeCP();
								}
								app.execAsync(e.getCommand(), info);
							}
							if (seqNo % APP_CP_INTERVAL == APP_CP_INTERVAL - 1) {
								state.startSync(seqNo);
								app.sync();
							}
							lastSeqNoExecuted = seqNo;
						}
					}
					// Need fix here
					/*
					 * state.startConsume(0, LOG_FILE_PREFIX +
					 * toLoad.getFileName()); app.consumeLog(LOG_FILE_PREFIX +
					 * toLoad.getFileName()); //
					 * System.out.println("WaitForConsumeDone");
					 * state.waitForConsumeDone(0);
					 */
					this.state.getLastCPToken().addLogToken(toLoad);
					this.state.reset(toLoad.getSeqNo());
					System.out.println("Add " + toLoad + " to lastCPToken");
					// System.out.println("After WaitForConsumeDone");
					// state.readyToConsume(toLoad.getSeqNo(), LOG_FILE_PREFIX
					// + toLoad.getFileName());
					if (toLoad.getSeqNo() % APP_CP_INTERVAL == APP_CP_INTERVAL - 1) {
						if (toLoad.getSeqNo()
								- state.getLastCPToken().getCPSeqNo() == 2 * APP_CP_INTERVAL) {
							hashThread.handleLastCPToken(toLoad.getSeqNo()
									- APP_CP_INTERVAL);
						}
					}
					iter.remove();
				} else
					break;
			}
			}
			if (this.logsToLoad.size() == 0) {
				this.inLoadCP = false;
				// mainApp.waitForConsume();
				// this.replayCache();
				this.execThread = new ExecThread();
				this.execThread.start();
				System.out.println("Call readyForRequests");
				shim.readyForRequests();
				System.out.println("RecoveryTime="
						+ (System.currentTimeMillis() - startTime));
			} else {
				System.out.println("Remaining0 " + logsToLoad.get(0));
				System.out.println("Remaining" + (logsToLoad.size() - 1) + " "
						+ logsToLoad.get(logsToLoad.size() - 1));
			}
		}
	}

	public void execDone(byte[] reply, RequestInfo info) {
		shim.result(reply, info.getClientId(), info.getRequestId(), info
				.getSeqNo(), true);
		state.execDone(reply, info);
	}

	public void execReadonlyDone(byte[] reply, int clientId, long requestId) {
		shim.readOnlyResult(reply, clientId, requestId);
	}

	public void sendEvent(byte[] event, int clientId, long eventId){
		shim.result(event, clientId, eventId, 0, false);
	}

	public void syncDone(String fileName) {
		state.syncDone(fileName);
	}

	/*public void loadSnapshotDone() {
		state.loadSnapshotDone();
	}*/

	public InetAddress getIP(int clientId) {
		return shim.getIP(clientId);
	}

	public int getPort(int clientId) {
		return shim.getPort(clientId);
	}

}
