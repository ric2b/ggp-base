package org.ggp.base.player.gamer.statemachine.sancho;

import org.apache.logging.log4j.ThreadContext;
import org.ggp.base.player.gamer.statemachine.sancho.LocalRegionSearcher.LocalSearchController;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class MoveConsequenceSearcher implements Runnable, LocalSearchController
{
  private boolean mTerminateRequested = false;
  private volatile int searchRequested = -1;
  private int searchProcessing = -1;
  private int nextSearch = 0;
  private ForwardDeadReckonInternalMachineState currentState;
  private ForwardDeadReckonLegalMoveInfo        seedMove;

  private final LocalRegionSearcher regionSearcher;
  private final String mLogName;

  public MoveConsequenceSearcher(
                    ForwardDeadReckonPropnetStateMachine xiUnderlyingStateMachine,
                    RoleOrdering xiRoleOrdering,
                    String logName)
  {
    regionSearcher = new LocalRegionSearcher(xiUnderlyingStateMachine, xiRoleOrdering, this);
    mLogName = logName;
  }

  public void stop()
  {
    mTerminateRequested = true;
  }

  public void newSearch(ForwardDeadReckonInternalMachineState startState,
                        ForwardDeadReckonLegalMoveInfo seed)
  {
    //  If we're being asked for the same search again just continue
    if ( seedMove != seed || !startState.equals(currentState) )
    {
      synchronized(this)
      {
        currentState = startState;
        seedMove = seed;

        searchRequested = ++nextSearch;

        this.notifyAll();
      }
    }
  }

  public void endSearch()
  {
    synchronized(this)
    {
      searchRequested = -1;
    }
  }

  @Override
  public void run()
  {
    // Register this thread.
    ThreadContext.put("matchID", mLogName);
    ThreadControl.registerSearchThread();

    while(!mTerminateRequested)
    {
      synchronized(this)
      {
        if ( searchRequested == -1 )
        {
          try
          {
            this.wait();
          }
          catch (InterruptedException e)
          {
            // TODO Auto-generated catch block
            e.printStackTrace();
          }
        }

        if ( searchRequested != searchProcessing )
        {
          //  Start a new search
          regionSearcher.setSearchParameters(currentState, seedMove);
          searchProcessing = searchRequested;
        }
      }

      if ( regionSearcher.iterate() )
      {
        // Current search complete
        synchronized(this)
        {
          if ( searchRequested == searchProcessing )
          {
            searchRequested = -1;
          }
        }
      }
    }
  }

  @Override
  public boolean terminateSearch()
  {
    return (mTerminateRequested || searchProcessing != searchRequested);
  }
}
