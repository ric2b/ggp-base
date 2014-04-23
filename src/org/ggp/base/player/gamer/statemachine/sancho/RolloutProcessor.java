package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

class RolloutProcessor implements Runnable
{
  private final RolloutProcessorPool                 pool;
  private final ForwardDeadReckonPropnetStateMachine stateMachine;
  private boolean                                    stop;
  private Thread                                     runningThread;
  public static final boolean                        useTerminalityHorizon = false; //  Work-in-progress - disable for commit for now
  private final int                                  rolloutTerminalityHorizon = (useTerminalityHorizon ? 5 : 500);

  public RolloutProcessor(RolloutProcessorPool pool, ForwardDeadReckonPropnetStateMachine stateMachine)
  {
    this.pool = pool;
    this.stateMachine = stateMachine;

    stateMachine.setTerminalCheckHorizon(rolloutTerminalityHorizon);
  }

  public void disableGreedyRollouts()
  {
    stateMachine.disableGreedyRollouts();
  }

  public void start()
  {
    if (runningThread == null)
    {
      runningThread = new Thread(this, "Rollout Processor");
      runningThread.setDaemon(true);
      runningThread.start();
    }
  }

  public void stop()
  {
    stop = true;

    if (runningThread != null)
    {
      runningThread.interrupt();
      runningThread = null;
    }
  }

  public void clearTerminatingMoveProps()
  {
    synchronized(this)
    {
      stateMachine.clearTerminatingMoveProps();
    }
  }

  @Override
  public void run()
  {
    try
    {
      while (!stop)
      {
        RolloutRequest request = pool.queuedRollouts.take();

        try
        {
          synchronized(this)
          {
            request.process(stateMachine);
          }
        }
        catch (TransitionDefinitionException e)
        {
          e.printStackTrace();
        }
        catch (MoveDefinitionException e)
        {
          e.printStackTrace();
        }
        catch (GoalDefinitionException e)
        {
          e.printStackTrace();
        }
      }
    }
    catch (InterruptedException ie)
    {
      // This is completely expected (if we stop this thread whilst it's waiting for a queue item).
    }
  }
}