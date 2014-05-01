package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class RolloutProcessorPool
{
  private final BlockingQueue<RolloutRequest>          queuedRollouts;
  ConcurrentLinkedQueue<RolloutRequest>                completedRollouts      = new ConcurrentLinkedQueue<>();
  public int                                           numQueuedRollouts      = 0;
  public int                                           numCompletedRollouts   = 0;
  private final int                                    numRolloutThreads;
  private RolloutProcessor[]                           rolloutProcessors      = null;
  public int                                           numRoles;
  public Role                                          ourRole;
  public RoleOrdering                                  roleOrdering           = null;
  private ForwardDeadReckonPropnetStateMachine         underlyingStateMachine;
  public int highestRolloutScoreSeen;
  public int lowestRolloutScoreSeen;
  private long                                         mEnqueueTime;
  private AtomicLong                                   mDequeueTime           = new AtomicLong(0);

  public RolloutProcessorPool(int numThreads, ForwardDeadReckonPropnetStateMachine underlyingStateMachine, Role ourRole)
  {
    numRolloutThreads = numThreads;
    queuedRollouts = new ArrayBlockingQueue<>(numThreads * 3);
    rolloutProcessors = new RolloutProcessor[numThreads];
    numRoles = underlyingStateMachine.getRoles().size();
    this.ourRole = ourRole;
    this.underlyingStateMachine = underlyingStateMachine;

    for (int i = 0; i < numThreads; i++)
    {
      rolloutProcessors[i] = new RolloutProcessor(this, underlyingStateMachine.createInstance());
      rolloutProcessors[i].start();
    }
  }

  public void noteNewTurn()
  {
    lowestRolloutScoreSeen = 1000;
    highestRolloutScoreSeen = -100;

    if (RolloutProcessor.useTerminalityHorizon)
    {
      for (int i = 0; i < numRolloutThreads; i++)
      {
        rolloutProcessors[i].clearTerminatingMoveProps();
      }
    }

    System.out.println("In last turn, consumers blocked for avg: " +
                                                         (mDequeueTime.getAndSet(0) / 1000000 / numRolloutThreads) + "ms");
    System.out.println("In last turn, supplier blocked for:      " + (mEnqueueTime / 1000000) + "ms");
    mEnqueueTime = 0;
  }

  public void setRoleOrdering(RoleOrdering ordering)
  {
    roleOrdering = ordering;
  }

  // !! ARR This method shouldn't be necessary.  We should immediately process rollouts on the main thread at the
  // !! ARR point where they would normally be queued.
  public void processQueueWithoutThreads() throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
  {
     if (numRolloutThreads == 0)
     {
       while (!queuedRollouts.isEmpty())
       {
         RolloutRequest request = queuedRollouts.remove();
         request.process(underlyingStateMachine);
       }
     }
   }

  public void stop()
  {
    if (rolloutProcessors != null)
    {
      System.out.println("Stop rollout processors");
      for (int i = 0; i < numRolloutThreads; i++)
      {
        rolloutProcessors[i].stop();
      }

      rolloutProcessors = null;
    }
  }

  public void disableGreedyRollouts()
  {
    for (int i = 0; i < numRolloutThreads; i++)
    {
      rolloutProcessors[i].disableGreedyRollouts();
    }
  }

  public RolloutRequest createRolloutRequest()
  {
    return new RolloutRequest(this);
  }

  /**
   * Enqueue a rollout request for processing.
   *
   * If the work backlog has grown to the limit, this method will block until a queue slot is available (or until
   * interrupted).
   *
   * @param request - the rollout request.
   *
   * @throws InterruptedException if the thread was interrupted whilst waiting to queue the request.
   */
  public void enqueueRequest(RolloutRequest request) throws InterruptedException
  {
    numQueuedRollouts++;
    long enqueueStart = System.nanoTime();
    queuedRollouts.put(request);
    mEnqueueTime += (System.nanoTime() - enqueueStart);
  }

  /**
   * Get a rollout request to work on.
   *
   * If there is no available work, this method will block until there is work to do (or until interrupted).
   *
   * @return a rollout request.
   *
   * @throws InterruptedException if the thread was interrupted whilst waiting to dequeue the request.
   */
  public RolloutRequest dequeueRequest() throws InterruptedException
  {
    long enqueueStart = System.nanoTime();
    RolloutRequest lRequest = queuedRollouts.take();
    mDequeueTime.addAndGet(System.nanoTime() - enqueueStart);
    return lRequest;
  }
}
