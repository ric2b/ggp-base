package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class RolloutProcessorPool
{
  private final BlockingQueue<RolloutRequest>          queuedRollouts;
  private final int                                    numRolloutThreads;
  private RolloutProcessor[]                           rolloutProcessors      = null;
  public final int                                     numRoles;
  public Role                                          ourRole;
  public RoleOrdering                                  roleOrdering           = null;
  private ForwardDeadReckonPropnetStateMachine         underlyingStateMachine;
  public int highestRolloutScoreSeen;
  public int lowestRolloutScoreSeen;

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
  }

  public void setRoleOrdering(RoleOrdering ordering)
  {
    roleOrdering = ordering;
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

  /**
   * Enqueue a rollout request for processing.
   *
   * If the work backlog has grown to the limit, this method will block until a queue slot is available (or until
   * interrupted).
   *
   * @param xiRequest - the rollout request.
   *
   * @throws InterruptedException if the thread was interrupted whilst waiting to queue the request.
   */
  public void enqueueRequest(RolloutRequest xiRequest) throws InterruptedException
  {
    if (numRolloutThreads > 0)
    {
      // We're doing asynchronous rollouts so queue the request for later.
      xiRequest.completeTreeWork();
      queuedRollouts.put(xiRequest);
    }
    else
    {
      // We're doing synchronous rollouts so just process the request now.
      assert(numRolloutThreads == 0);
      xiRequest.process(underlyingStateMachine);
    }
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
    assert(numRolloutThreads > 0);
    RolloutRequest lRequest = queuedRollouts.take();
    return lRequest;
  }

  /**
   * @return the number of rollout threads in this pool.
   */
  public int getNumThreads()
  {
    return numRolloutThreads;
  }
}
