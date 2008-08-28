/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;
import com.db4o.query.Query;
import com.db4o.types.Db4oList;
import com.db4o.types.Db4oMap;

import freenet.client.FetchContext;
import freenet.crypt.RandomSource;
import freenet.keys.ClientKey;
import freenet.keys.Key;
import freenet.node.BaseSendableGet;
import freenet.node.KeysFetchingLocally;
import freenet.node.Node;
import freenet.node.RequestStarter;
import freenet.node.SendableGet;
import freenet.node.SendableRequest;
import freenet.support.Db4oSet;
import freenet.support.Logger;
import freenet.support.PrioritizedSerialExecutor;
import freenet.support.RandomGrabArray;
import freenet.support.SectoredRandomGrabArrayWithInt;
import freenet.support.SectoredRandomGrabArrayWithObject;
import freenet.support.SortedVectorByNumber;
import freenet.support.io.NativeThread;

/**
 * @author toad
 * A persistent class that functions as the core of the ClientRequestScheduler.
 * Does not refer to any non-persistable classes as member variables: Node must always 
 * be passed in if we need to use it!
 */
class ClientRequestSchedulerCore extends ClientRequestSchedulerBase implements KeysFetchingLocally {
	
	private static boolean logMINOR;
	/** Identifier in the database for the node we are attached to */
	private final long nodeDBHandle;
	final PersistentCooldownQueue persistentCooldownQueue;
	private transient long initTime;
	
	/**
	 * All Key's we are currently fetching. 
	 * Locally originated requests only, avoids some complications with HTL, 
	 * and also has the benefit that we can see stuff that's been scheduled on a SenderThread
	 * but that thread hasn't started yet. FIXME: Both issues can be avoided: first we'd get 
	 * rid of the SenderThread and start the requests directly and asynchronously, secondly
	 * we'd move this to node but only track keys we are fetching at max HTL.
	 * LOCKING: Always lock this LAST.
	 */
	private transient HashSet keysFetching;
	
	public final byte[] globalSalt;
	
	/**
	 * Fetch a ClientRequestSchedulerCore from the database, or create a new one.
	 * @param node
	 * @param forInserts
	 * @param forSSKs
	 * @param selectorContainer
	 * @param executor 
	 * @return
	 */
	public static ClientRequestSchedulerCore create(Node node, final boolean forInserts, final boolean forSSKs, ObjectContainer selectorContainer, long cooldownTime, PrioritizedSerialExecutor databaseExecutor, ClientRequestScheduler sched, ClientContext context) {
		final long nodeDBHandle = node.nodeDBHandle;
		ObjectSet<ClientRequestSchedulerCore> results = selectorContainer.query(new Predicate<ClientRequestSchedulerCore>() {
			public boolean match(ClientRequestSchedulerCore core) {
				if(core.nodeDBHandle != nodeDBHandle) return false;
				if(core.isInsertScheduler != forInserts) return false;
				if(core.isSSKScheduler != forSSKs) return false;
				return true;
			}
		});
		ClientRequestSchedulerCore core;
		if(results.hasNext()) {
			core = results.next();
			selectorContainer.activate(core, 2);
			System.err.println("Loaded core...");
			if(core.nodeDBHandle != nodeDBHandle) throw new IllegalStateException("Wrong nodeDBHandle");
			if(core.isInsertScheduler != forInserts) throw new IllegalStateException("Wrong isInsertScheduler");
			if(core.isSSKScheduler != forSSKs) throw new IllegalStateException("Wrong forSSKs");
		} else {
			core = new ClientRequestSchedulerCore(node, forInserts, forSSKs, selectorContainer, cooldownTime);
			selectorContainer.set(core);
			System.err.println("Created new core...");
		}
		logMINOR = Logger.shouldLog(Logger.MINOR, ClientRequestSchedulerCore.class);
		core.onStarted(selectorContainer, cooldownTime, sched, context);
		return core;
	}

	ClientRequestSchedulerCore(Node node, boolean forInserts, boolean forSSKs, ObjectContainer selectorContainer, long cooldownTime) {
		super(forInserts, forSSKs, selectorContainer.ext().collections().newLinkedList());
		this.nodeDBHandle = node.nodeDBHandle;
		if(!forInserts) {
			this.persistentCooldownQueue = new PersistentCooldownQueue();
		} else {
			this.persistentCooldownQueue = null;
		}
		globalSalt = new byte[32];
		node.random.nextBytes(globalSalt);
	}

	private void onStarted(ObjectContainer container, long cooldownTime, ClientRequestScheduler sched, ClientContext context) {
		super.onStarted();
		System.err.println("insert scheduler: "+isInsertScheduler);
		if(recentSuccesses == null)
			System.err.println("recentSuccesses is null");
		((Db4oList)recentSuccesses).activationDepth(1);
		if(!isInsertScheduler) {
			persistentCooldownQueue.setCooldownTime(cooldownTime);
		}
		this.sched = sched;
		this.initTime = System.currentTimeMillis();
		// We DO NOT want to rerun the query after consuming the initial set...
		if(!isInsertScheduler)
			keysFetching = new HashSet();
		else
			keysFetching = null;
		preRegisterMeRunner = new DBJob() {

			public void run(ObjectContainer container, ClientContext context) {
				synchronized(ClientRequestSchedulerCore.this) {
					if(registerMeSet != null) return;
				}
				long tStart = System.currentTimeMillis();
				// FIXME REDFLAG EVIL DB4O BUG!!!
				// FIXME verify and file a bug
				// This code doesn't check the first bit!
				// I think this is related to the comparator...
//				registerMeSet = container.query(new Predicate() {
//					public boolean match(RegisterMe reg) {
//						if(reg.core != ClientRequestSchedulerCore.this) return false;
//						if(reg.key.addedTime > initTime) return false;
//						return true;
//					}
//				}, new Comparator() {
//
//					public int compare(Object arg0, Object arg1) {
//						RegisterMe reg0 = (RegisterMe) arg0;
//						RegisterMe reg1 = (RegisterMe) arg1;
//						RegisterMeSortKey key0 = reg0.key;
//						RegisterMeSortKey key1 = reg1.key;
//						return key0.compareTo(key1);
//					}
//					
//				});
				Query query = container.query();
				query.constrain(RegisterMe.class);
				// This throws NotSupported.
//				query.descend("core").constrain(this).identity().
//					and(query.descend("key").descend("addedTime").constrain(new Long(initTime)).smaller());
				/**
				 * FIXME DB4O
				 * db4o says it has indexed core. But then when we try to query, it produces a diagnostic
				 * suggesting we index it. And of course the query takes ages and uses tons of RAM. So don't
				 * try to filter by core at this point, deal with that later.
				 */
//				query.descend("core").constrain(ClientRequestSchedulerCore.this);
//				Evaluation eval = new Evaluation() {
//
//					public void evaluate(Candidate candidate) {
//						RegisterMe reg = (RegisterMe) candidate.getObject();
//						if(reg.key.addedTime > initTime || reg.core != ClientRequestSchedulerCore.this) {
//							candidate.include(false);
//							candidate.objectContainer().deactivate(reg.key, 1);
//							candidate.objectContainer().deactivate(reg, 1);
//						} else {
//							candidate.include(true);
//						}
//					}
//					
//				};
//				query.constrain(eval);
//				query.descend("key").descend("priority").orderAscending();
//				query.descend("key").descend("addedTime").orderAscending();
				ObjectSet results = query.execute();
				synchronized(ClientRequestSchedulerCore.this) {
					registerMeSet = results;
				}
			long tEnd = System.currentTimeMillis();
			if(logMINOR)
				Logger.minor(this, "RegisterMe query took "+(tEnd-tStart)+" hasNext="+registerMeSet.hasNext()+" for insert="+isInsertScheduler+" ssk="+isSSKScheduler);
//				if(logMINOR)
//					Logger.minor(this, "RegisterMe query returned: "+registerMeSet.size());
				boolean boost = ClientRequestSchedulerCore.this.sched.isQueueAlmostEmpty();

				context.jobRunner.queue(registerMeRunner, (NativeThread.NORM_PRIORITY-1) + (boost ? 2 : 0), true);
			}
			
		};
		registerMeRunner = new RegisterMeRunner();
		loadKeyListeners(container, context);
	}
	
	private void loadKeyListeners(final ObjectContainer container, ClientContext context) {
		ObjectSet<HasKeyListener> results =
			container.query(HasKeyListener.class);
		for(HasKeyListener l : results) {
			container.activate(l, 1);
			try {
				if(l.isCancelled(container)) continue;
				KeyListener listener = l.makeKeyListener(container, context);
				if(listener != null)
					addPendingKeys(listener);
			} catch (KeyListenerConstructionException e) {
				System.err.println("FAILED TO LOAD REQUEST BLOOM FILTERS:");
				e.printStackTrace();
				Logger.error(this, "FAILED TO LOAD REQUEST BLOOM FILTERS: "+e, e);
			}
			container.deactivate(l, 1);
		}
	}

	private transient DBJob preRegisterMeRunner;
	
	void start(DBJobRunner runner) {
		startRegisterMeRunner(runner);
	}
	
	private final void startRegisterMeRunner(DBJobRunner runner) {
		runner.queue(preRegisterMeRunner, NativeThread.NORM_PRIORITY, true);
	}
	
	// We pass in the schedTransient to the next two methods so that we can select between either of them.
	
	private int removeFirstAccordingToPriorities(boolean tryOfferedKeys, int fuzz, RandomSource random, OfferedKeysList[] offeredKeys, ClientRequestSchedulerNonPersistent schedTransient, boolean transientOnly, short maxPrio, ObjectContainer container){
		SortedVectorByNumber result = null;
		
		short iteration = 0, priority;
		// we loop to ensure we try every possibilities ( n + 1)
		//
		// PRIO will do 0,1,2,3,4,5,6,0
		// TWEAKED will do rand%6,0,1,2,3,4,5,6
		while(iteration++ < RequestStarter.NUMBER_OF_PRIORITY_CLASSES + 1){
			priority = fuzz<0 ? tweakedPrioritySelector[random.nextInt(tweakedPrioritySelector.length)] : prioritySelector[Math.abs(fuzz % prioritySelector.length)];
			if(transientOnly)
				result = null;
			else
				result = priorities[priority];
			if(result == null)
				result = schedTransient.priorities[priority];
			if(priority > maxPrio) {
				fuzz++;
				continue; // Don't return because first round may be higher with soft scheduling
			}
			if((result != null) && 
					(!result.isEmpty()) || (tryOfferedKeys && !offeredKeys[priority].isEmpty(container))) {
				if(logMINOR) Logger.minor(this, "using priority : "+priority);
				return priority;
			}
			
			if(logMINOR) Logger.minor(this, "Priority "+priority+" is null (fuzz = "+fuzz+ ')');
			fuzz++;
		}
		
		//FIXME: implement NONE
		return -1;
	}
	
	// LOCKING: ClientRequestScheduler locks on (this) before calling. 
	// We prevent a number of race conditions (e.g. adding a retry count and then another 
	// thread removes it cos its empty) ... and in addToGrabArray etc we already sync on this.
	// The worry is ... is there any nested locking outside of the hierarchy?
	ChosenBlock removeFirst(int fuzz, RandomSource random, OfferedKeysList[] offeredKeys, RequestStarter starter, ClientRequestSchedulerNonPersistent schedTransient, boolean transientOnly, boolean notTransient, short maxPrio, int retryCount, ClientContext context, ObjectContainer container) {
		SendableRequest req = removeFirstInner(fuzz, random, offeredKeys, starter, schedTransient, transientOnly, notTransient, maxPrio, retryCount, context, container);
		if(isInsertScheduler && req instanceof SendableGet) {
			IllegalStateException e = new IllegalStateException("removeFirstInner returned a SendableGet on an insert scheduler!!");
			req.internalError(e, sched, container, context, req.persistent());
			throw e;
		}
		return maybeMakeChosenRequest(req, container, context);
	}
	
	private int ctr;
	
	public ChosenBlock maybeMakeChosenRequest(SendableRequest req, ObjectContainer container, ClientContext context) {
		if(req == null) return null;
		if(req.isEmpty(container) || req.isCancelled(container)) return null;
		Object token = req.chooseKey(this, req.persistent() ? container : null, context);
		if(token == null) {
			return null;
		} else {
			Key key;
			ClientKey ckey;
			if(isInsertScheduler) {
				key = null;
				ckey = null;
			} else {
				key = ((BaseSendableGet)req).getNodeKey(token, persistent() ? container : null);
				if(req instanceof SendableGet)
					ckey = ((SendableGet)req).getKey(token, persistent() ? container : null);
				else
					ckey = null;
			}
			ChosenBlock ret;
			assert(!req.persistent());
			if(key != null && key.getRoutingKey() == null)
				throw new NullPointerException();
			boolean localRequestOnly;
			boolean cacheLocalRequests;
			boolean ignoreStore;
			if(req instanceof SendableGet) {
				SendableGet sg = (SendableGet) req;
				FetchContext ctx = sg.getContext();
				if(container != null)
					container.activate(ctx, 1);
				localRequestOnly = ctx.localRequestOnly;
				cacheLocalRequests = ctx.cacheLocalRequests;
				ignoreStore = ctx.ignoreStore;
			} else {
				localRequestOnly = false;
				cacheLocalRequests = false;
				ignoreStore = false;
			}
			ret = new TransientChosenBlock(req, token, key, ckey, localRequestOnly, cacheLocalRequests, ignoreStore, sched);
			return ret;
		}
	}

	SendableRequest removeFirstInner(int fuzz, RandomSource random, OfferedKeysList[] offeredKeys, RequestStarter starter, ClientRequestSchedulerNonPersistent schedTransient, boolean transientOnly, boolean notTransient, short maxPrio, int retryCount, ClientContext context, ObjectContainer container) {
		// Priorities start at 0
		if(logMINOR) Logger.minor(this, "removeFirst()");
		boolean tryOfferedKeys = offeredKeys != null && (!notTransient) && random.nextBoolean();
		int choosenPriorityClass = removeFirstAccordingToPriorities(tryOfferedKeys, fuzz, random, offeredKeys, schedTransient, transientOnly, maxPrio, container);
		if(choosenPriorityClass == -1 && offeredKeys != null && (!tryOfferedKeys) && (!notTransient)) {
			tryOfferedKeys = true;
			choosenPriorityClass = removeFirstAccordingToPriorities(tryOfferedKeys, fuzz, random, offeredKeys, schedTransient, transientOnly, maxPrio, container);
		}
		if(choosenPriorityClass == -1) {
			if(logMINOR)
				Logger.minor(this, "Nothing to do");
			return null;
		}
		for(;choosenPriorityClass <= RequestStarter.MINIMUM_PRIORITY_CLASS;choosenPriorityClass++) {
			if(logMINOR) Logger.minor(this, "Using priority "+choosenPriorityClass);
		if(tryOfferedKeys) {
			if(offeredKeys[choosenPriorityClass].hasValidKeys(this, null, context))
				return offeredKeys[choosenPriorityClass];
		}
		SortedVectorByNumber perm = null;
		if(!transientOnly)
			perm = priorities[choosenPriorityClass];
		SortedVectorByNumber trans = null;
		if(!notTransient)
			trans = schedTransient.priorities[choosenPriorityClass];
		if(perm == null && trans == null) {
			if(logMINOR) Logger.minor(this, "No requests to run: chosen priority empty");
			continue; // Try next priority
		}
		int permRetryIndex = 0;
		int transRetryIndex = 0;
		while(true) {
			int permRetryCount = perm == null ? Integer.MAX_VALUE : perm.getNumberByIndex(permRetryIndex);
			int transRetryCount = trans == null ? Integer.MAX_VALUE : trans.getNumberByIndex(transRetryIndex);
			if(choosenPriorityClass == maxPrio) {
				if(permRetryCount >= retryCount) {
					permRetryCount = Integer.MAX_VALUE;
				}
				if(transRetryCount >= retryCount) {
					transRetryCount = Integer.MAX_VALUE;
				}
			}
			if(permRetryCount == Integer.MAX_VALUE && transRetryCount == Integer.MAX_VALUE) {
				if(logMINOR) Logger.minor(this, "No requests to run: ran out of retrycounts on chosen priority");
				break; // Try next priority
			}
			SectoredRandomGrabArrayWithInt chosenTracker = null;
			SortedVectorByNumber trackerParent = null;
			if(permRetryCount == transRetryCount) {
				// Choose between them.
				SectoredRandomGrabArrayWithInt permRetryTracker = (SectoredRandomGrabArrayWithInt) perm.getByIndex(permRetryIndex);
				if(persistent() && permRetryTracker != null)
					container.activate(permRetryTracker, 1);
				SectoredRandomGrabArrayWithInt transRetryTracker = (SectoredRandomGrabArrayWithInt) trans.getByIndex(transRetryIndex);
				int permTrackerSize = permRetryTracker.size();
				int transTrackerSize = transRetryTracker.size();
				if(permTrackerSize + transTrackerSize == 0) {
					permRetryIndex++;
					transRetryIndex++;
					continue;
				}
				if(random.nextInt(permTrackerSize + transTrackerSize) > permTrackerSize) {
					chosenTracker = permRetryTracker;
					trackerParent = perm;
					permRetryIndex++;
				} else {
					chosenTracker = transRetryTracker;
					trackerParent = trans;
					transRetryIndex++;
				}
			} else if(permRetryCount < transRetryCount) {
				chosenTracker = (SectoredRandomGrabArrayWithInt) perm.getByIndex(permRetryIndex);
				if(persistent() && chosenTracker != null)
					container.activate(chosenTracker, 1);
				trackerParent = perm;
				permRetryIndex++;
			} else {
				chosenTracker = (SectoredRandomGrabArrayWithInt) trans.getByIndex(transRetryIndex);
				trackerParent = trans;
				transRetryIndex++;
			}
			if(logMINOR)
				Logger.minor(this, "Got retry count tracker "+chosenTracker);
			SendableRequest req = (SendableRequest) chosenTracker.removeRandom(starter, container, context);
			if(chosenTracker.isEmpty()) {
				trackerParent.remove(chosenTracker.getNumber(), container);
				if(trackerParent.isEmpty()) {
					if(logMINOR) Logger.minor(this, "Should remove priority");
				}
			}
			if(req == null) {
				if(logMINOR) Logger.minor(this, "No requests, adjusted retrycount "+chosenTracker.getNumber()+" ("+chosenTracker+") of priority "+choosenPriorityClass);
				continue; // Try next retry count.
			}
			if(chosenTracker.persistent())
				container.activate(req, 1); // FIXME
			if(req.persistent() != trackerParent.persistent()) {
				Logger.error(this, "Request.persistent()="+req.persistent()+" but is in the queue for persistent="+trackerParent.persistent()+" for "+req);
				// FIXME fix it
			}
			if(req.getPriorityClass(container) != choosenPriorityClass) {
				// Reinsert it : shouldn't happen if we are calling reregisterAll,
				// maybe we should ask people to report that error if seen
				Logger.normal(this, "In wrong priority class: "+req+" (req.prio="+req.getPriorityClass(container)+" but chosen="+choosenPriorityClass+ ')');
				// Remove it.
				SectoredRandomGrabArrayWithObject clientGrabber = (SectoredRandomGrabArrayWithObject) chosenTracker.getGrabber(req.getClient());
				if(clientGrabber != null) {
					if(chosenTracker.persistent())
						container.activate(clientGrabber, 1);
					RandomGrabArray baseRGA = (RandomGrabArray) clientGrabber.getGrabber(req.getClientRequest());
					if(baseRGA != null) {
						baseRGA.remove(req, container);
					} else {
						// Okay, it's been removed already. Cool.
					}
				} else {
					Logger.error(this, "Could not find client grabber for client "+req.getClient()+" from "+chosenTracker);
				}
				if(req.persistent())
					innerRegister(req, random, container);
				else
					schedTransient.innerRegister(req, random, container);
				continue; // Try the next one on this retry count.
			}
			
			// Check recentSuccesses
			/** FIXME:
			 * This does not make sense with selecting a whole SendableRequest
			 * at a time, so turn it off. However, what would make sense would 
			 * be to have recentSuccesses be a list of ClientRequester's instead, 
			 * like we do on trunk. */
			if(!req.persistent()) {
			List recent = req.persistent() ? recentSuccesses : schedTransient.recentSuccesses;
			SendableRequest altReq = null;
			if(!recent.isEmpty()) {
				if(random.nextBoolean()) {
					altReq = (BaseSendableGet) recent.remove(recent.size()-1);
				}
			}
			if(altReq != null)
				container.activate(altReq, 1);
			if(altReq != null && (altReq.isCancelled(container) || altReq.isEmpty(container))) {
				if(logMINOR)
					Logger.minor(this, "Ignoring cancelled recently succeeded item "+altReq);
				altReq = null;
			}
			if(altReq != null && altReq.getPriorityClass(container) <= choosenPriorityClass && 
					fixRetryCount(altReq.getRetryCount()) <= chosenTracker.getNumber() && !altReq.isEmpty(container) && altReq != req) {
				// Use the recent one instead
				if(logMINOR)
					Logger.minor(this, "Recently succeeded req "+altReq+" is better, using that, reregistering chosen "+req);
				if(req.persistent())
					innerRegister(req, random, container);
				else
					schedTransient.innerRegister(req, random, container);
				req = altReq;
			} else if(altReq != null) {
				// Don't use the recent one
				if(logMINOR)
					Logger.minor(this, "Chosen req "+req+" is better, reregistering recently succeeded "+altReq);
				recent.add(altReq);
			}
			}
			
			// Now we have chosen a request.
			if(logMINOR) Logger.minor(this, "removeFirst() returning "+req+" ("+chosenTracker.getNumber()+", prio "+
					req.getPriorityClass(container)+", retries "+req.getRetryCount()+", client "+req.getClient()+", client-req "+req.getClientRequest()+ ')');
			ClientRequester cr = req.getClientRequest();
			if(req.canRemove(container)) {
				if(req.persistent())
					removeFromAllRequestsByClientRequest(req, cr, false, container);
				else
					schedTransient.removeFromAllRequestsByClientRequest(req, cr, false, null);
				// Do not remove from the pendingKeys list.
				// Whether it is running a request, waiting to execute, or waiting on the
				// cooldown queue, ULPRs and backdoor coalescing should still be active.
			}
			if(logMINOR) Logger.minor(this, "removeFirst() returning "+req+" of "+req.getClientRequest());
			return req;
			
		}
		}
		if(logMINOR) Logger.minor(this, "No requests to run");
		return null;
	}
	
	private static final short[] tweakedPrioritySelector = { 
		RequestStarter.MAXIMUM_PRIORITY_CLASS,
		RequestStarter.MAXIMUM_PRIORITY_CLASS,
		RequestStarter.MAXIMUM_PRIORITY_CLASS,
		RequestStarter.MAXIMUM_PRIORITY_CLASS,
		RequestStarter.MAXIMUM_PRIORITY_CLASS,
		RequestStarter.MAXIMUM_PRIORITY_CLASS,
		RequestStarter.MAXIMUM_PRIORITY_CLASS,
		
		RequestStarter.INTERACTIVE_PRIORITY_CLASS,
		RequestStarter.INTERACTIVE_PRIORITY_CLASS,
		RequestStarter.INTERACTIVE_PRIORITY_CLASS,
		RequestStarter.INTERACTIVE_PRIORITY_CLASS,
		RequestStarter.INTERACTIVE_PRIORITY_CLASS,
		RequestStarter.INTERACTIVE_PRIORITY_CLASS,
		
		RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS,
		RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS,
		RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, 
		RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, 
		RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS,
		
		RequestStarter.UPDATE_PRIORITY_CLASS,
		RequestStarter.UPDATE_PRIORITY_CLASS, 
		RequestStarter.UPDATE_PRIORITY_CLASS, 
		RequestStarter.UPDATE_PRIORITY_CLASS,
		
		RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, 
		RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, 
		RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS,
		
		RequestStarter.PREFETCH_PRIORITY_CLASS, 
		RequestStarter.PREFETCH_PRIORITY_CLASS,
		
		RequestStarter.MINIMUM_PRIORITY_CLASS
	};
	private static final short[] prioritySelector = {
		RequestStarter.MAXIMUM_PRIORITY_CLASS,
		RequestStarter.INTERACTIVE_PRIORITY_CLASS,
		RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, 
		RequestStarter.UPDATE_PRIORITY_CLASS,
		RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS,
		RequestStarter.PREFETCH_PRIORITY_CLASS,
		RequestStarter.MINIMUM_PRIORITY_CLASS
	};

	boolean persistent() {
		return true;
	}

	private transient ObjectSet registerMeSet;
	
	private transient RegisterMeRunner registerMeRunner;
	
	private transient boolean shouldReRunRegisterMeRunner;
	
	class RegisterMeRunner implements DBJob {

		public void run(ObjectContainer container, ClientContext context) {
			if(sched.databaseExecutor.getQueueSize(NativeThread.NORM_PRIORITY) > 100) {
				// If the queue isn't empty, reschedule at NORM-1
				if(!sched.isQueueAlmostEmpty()) {
					context.jobRunner.queue(registerMeRunner, NativeThread.NORM_PRIORITY-1, false);
					return;
				}
			}
			long deadline = System.currentTimeMillis() + 10*1000;
			if(registerMeSet == null) {
				Logger.error(this, "registerMeSet is null for "+ClientRequestSchedulerCore.this+" ( "+this+" )");
				return;
			}
			for(int i=0;i < 1000; i++) {
				try {
					if(!registerMeSet.hasNext()) break;
				} catch (NullPointerException t) {
					Logger.error(this, "DB4O thew NPE in hasNext(): "+t, t);
					// FIXME find some way to get a reproducible test case... I suspect it won't be easy :<
					context.jobRunner.queue(preRegisterMeRunner, NativeThread.NORM_PRIORITY, true);
					return;
				} catch (ClassCastException t) {
					// WTF?!?!?!?!?!
					Logger.error(this, "DB4O thew ClassCastException in hasNext(): "+t, t);
					// FIXME find some way to get a reproducible test case... I suspect it won't be easy :<
					context.jobRunner.queue(preRegisterMeRunner, NativeThread.NORM_PRIORITY, true);
					return;
				}
				long startNext = System.currentTimeMillis();
				RegisterMe reg = (RegisterMe) registerMeSet.next();
				container.activate(reg, 1);
				if(reg.bootID == context.bootID) {
					if(logMINOR) Logger.minor(this, "Not registering block as was added to the queue");
					continue;
				}
				// FIXME remove the leftover/old core handling at some point, an NPE is acceptable long-term.
				if(reg.core != ClientRequestSchedulerCore.this) {
					if(reg.core == null) {
						Logger.error(this, "Leftover RegisterMe: core already deleted. THIS IS AN ERROR unless you have seen \"Old core not active\" messages before this point.");
						container.delete(reg);
						continue;
					}
					if(!container.ext().isActive(reg.core)) {
						Logger.error(this, "Old core not active in RegisterMe - duplicated cores????");
						container.delete(reg.core);
						container.delete(reg);
						continue;
					}
					if(logMINOR)
						Logger.minor(this, "Ignoring RegisterMe as doesn't belong to me: my insert="+isInsertScheduler+" my ssk="+isSSKScheduler+" his insert="+reg.core.isInsertScheduler+" his ssk="+reg.core.isSSKScheduler);
					container.deactivate(reg, 1);
					continue; // Don't delete.
				}
				container.activate(reg.key, 1);
//				if(reg.key.addedTime > initTime) {
//					if(logMINOR) Logger.minor(this, "Ignoring RegisterMe as created since startup");
//					container.deactivate(reg.key, 1);
//					container.deactivate(reg, 1);
//					continue; // Don't delete
//				}
				long endNext = System.currentTimeMillis();
				if(logMINOR)
					Logger.minor(this, "RegisterMe: next() took "+(endNext-startNext));
				
				if(logMINOR)
					Logger.minor(this, "Running RegisterMe "+reg+" for "+reg.nonGetRequest+" : "+reg.key.addedTime+" : "+reg.key.priority);
				// Don't need to activate, fields should exist? FIXME
				if(reg.nonGetRequest != null) {
					container.activate(reg.nonGetRequest, 1);
					if(reg.nonGetRequest.isCancelled(container)) {
						Logger.normal(this, "RegisterMe: request cancelled: "+reg.nonGetRequest);
					} else {
						if(logMINOR)
							Logger.minor(this, "Registering RegisterMe for insert: "+reg.nonGetRequest);
						sched.registerInsert(reg.nonGetRequest, true, false, container);
					}
					container.delete(reg);
					container.deactivate(reg.nonGetRequest, 1);
				}
				container.deactivate(reg, 1);
				if(System.currentTimeMillis() > deadline) break;
			}
			boolean boost = sched.isQueueAlmostEmpty();
			if(registerMeSet.hasNext())
				context.jobRunner.queue(registerMeRunner, (NativeThread.NORM_PRIORITY-1) + (boost ? 2 : 0), true);
			else {
				if(logMINOR) Logger.minor(this, "RegisterMeRunner finished");
				boolean rerun;
				synchronized(ClientRequestSchedulerCore.this) {
					rerun = shouldReRunRegisterMeRunner;
					shouldReRunRegisterMeRunner = false;
					registerMeSet = null;
				}
				if(rerun) {
					preRegisterMeRunner.run(container, context);
				}
			}
		}
		
	}
	/**
	 * @return True unless the key was already present.
	 */
	public boolean addToFetching(Key key) {
		synchronized(keysFetching) {
			boolean retval = keysFetching.add(key);
			if(!retval) {
				Logger.normal(this, "Already in keysFetching: "+key);
			} else {
				if(logMINOR)
					Logger.minor(this, "Added to keysFetching: "+key);
			}
			return retval;
		}
	}
	
	public boolean hasKey(Key key) {
		if(keysFetching == null) {
			throw new NullPointerException();
		}
		synchronized(keysFetching) {
			return keysFetching.contains(key);
		}
	}

	public void removeFetchingKey(final Key key) {
		if(logMINOR)
			Logger.minor(this, "Removing from keysFetching: "+key);
		if(key != null) {
			synchronized(keysFetching) {
				keysFetching.remove(key);
			}
		}
	}
		
	protected Set makeSetForAllRequestsByClientRequest(ObjectContainer container) {
		return new Db4oSet(container, 1);
	}

	public void rerunRegisterMeRunner(DBJobRunner runner) {
		synchronized(this) {
			shouldReRunRegisterMeRunner = true;
			if(registerMeSet != null) return;
		}
		startRegisterMeRunner(runner);
	}

}

