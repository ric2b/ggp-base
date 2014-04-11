package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class RolloutProcessorPool
{
  LinkedBlockingQueue<RolloutRequest>                  queuedRollouts                              = new LinkedBlockingQueue<>();
  ConcurrentLinkedQueue<RolloutRequest>                completedRollouts                           = new ConcurrentLinkedQueue<>();
  public int                                           numQueuedRollouts                           = 0;
  public int                                           numCompletedRollouts                        = 0;
  public int                                           dequeuedRollouts                            = 0;
  public int                                           enqueuedCompletedRollouts                   = 0;
  private int                                          numRolloutThreads;
  private int                                          maxOutstandingRolloutRequests;
  private RolloutProcessor[]                           rolloutProcessors                           = null;
  public int                                           numRoles;
  public Role                                          ourRole;
  public RoleOrdering                                  roleOrdering                                = null;
  private ForwardDeadReckonPropnetStateMachine         underlyingStateMachine;
  public int highestRolloutScoreSeen;
  public int lowestRolloutScoreSeen;

  public RolloutProcessorPool(int numThreads, ForwardDeadReckonPropnetStateMachine underlyingStateMachine, Role ourRole)
  {
    numRolloutThreads = numThreads;
    maxOutstandingRolloutRequests = numThreads;
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

  public void setRoleOrdering(RoleOrdering ordering)
  {
    roleOrdering = ordering;
  }

  public boolean isBackedUp() throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
  {
    if (numRolloutThreads == 0)
    {
      while (!queuedRollouts.isEmpty())
      {
        RolloutRequest request = queuedRollouts.remove();

        request.process(underlyingStateMachine);
      }

      return false;
    }

    int numOutstandingRollouts = numQueuedRollouts - numCompletedRollouts;

    if (numOutstandingRollouts < maxOutstandingRolloutRequests)
    {
      return false;
    }

    Thread.yield();
    return true;
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

  public void enqueueRequest(RolloutRequest request) throws InterruptedException
  {
    numQueuedRollouts++;
    queuedRollouts.put(request);
  }
}
