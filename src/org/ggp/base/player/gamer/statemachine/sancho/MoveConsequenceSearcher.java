package org.ggp.base.player.gamer.statemachine.sancho;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.ggp.base.player.gamer.statemachine.sancho.LocalRegionSearcher.LocalSearchController;
import org.ggp.base.player.gamer.statemachine.sancho.LocalRegionSearcher.LocalSearchResultConsumer;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class MoveConsequenceSearcher implements Runnable, LocalSearchController
{
  private static final Logger LOGGER       = LogManager.getLogger();

  private boolean mTerminateRequested = false;
  private volatile int searchRequested = -1;
  private int searchProcessing = -1;
  private int nextSearch = 0;
  private ForwardDeadReckonInternalMachineState currentState;
  private ForwardDeadReckonInternalMachineState choiceFromState;
  private ForwardDeadReckonLegalMoveInfo        seedMove;
  private int                                   choosingRole;
  private boolean resetStats = false;
  private final Thread mThread;

  private final LocalRegionSearcher regionSearcher;
  private final String mLogName;

  public MoveConsequenceSearcher(
                    ForwardDeadReckonPropnetStateMachine xiUnderlyingStateMachine,
                    RoleOrdering xiRoleOrdering,
                    String logName,
                    LocalSearchResultConsumer resultConsumer)
  {
    regionSearcher = new LocalRegionSearcher(xiUnderlyingStateMachine, xiRoleOrdering, this, resultConsumer);
    mLogName = logName;

    mThread = new Thread(this, "Move Consequence Processor");
    mThread.setDaemon(true);
    mThread.start();
  }

  public void stop()
  {
    mTerminateRequested = true;

    synchronized(this)
    {
      this.notifyAll();
    }

    try
    {
      mThread.join(2000);
      if ( mThread.isAlive() )
      {
        LOGGER.warn("Local search processor failed to stop cleanly - interrupting");
        mThread.interrupt();
        mThread.join(5000);
      }
    }
    catch (InterruptedException lEx)
    {
      LOGGER.warn("Unexpectedly interrupted whilst stopping local search processor");
    }
  }

  public void newSearch(ForwardDeadReckonInternalMachineState startState,
                        ForwardDeadReckonInternalMachineState xiChoiceFromState,
                        ForwardDeadReckonLegalMoveInfo seed,
                        int xiChoosingRole,
                        boolean isNewTurn,
                        boolean forceSearchReset)
  {
    synchronized(this)
    {
      //  If we're being asked for the same search again just continue
      if ( seedMove == null || seedMove.masterIndex != seed.masterIndex || forceSearchReset || !startState.equals(currentState) )
      {
        LOGGER.info("New local search request - move " + ((seedMove == null || seedMove.masterIndex != seed.masterIndex) ?  "differs" : "does not differ") + ", state " + (startState.equals(currentState) ? "does not differ" : "differs"));
        currentState = new ForwardDeadReckonInternalMachineState(startState);
        if ( xiChoiceFromState != null )
        {
          choiceFromState = new ForwardDeadReckonInternalMachineState(xiChoiceFromState);
        }
        else
        {
          choiceFromState = null;
        }
        seedMove = seed;
        choosingRole = xiChoosingRole;

        searchRequested = ++nextSearch;

        this.notifyAll();
      }
      else
      {
        LOGGER.info("New search request ignored - matching state and seed move");
      }
      if ( isNewTurn )
      {
        LOGGER.info("Is new turn");
        resetStats |= isNewTurn;
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
          regionSearcher.setSearchParameters(currentState, choiceFromState, seedMove, choosingRole);
          searchProcessing = searchRequested;

          if ( resetStats )
          {
            regionSearcher.decayKillerStatistics();
            resetStats = false;
          }
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

    LOGGER.info("Local search thread exitting");
  }

  @Override
  public boolean terminateSearch()
  {
    return (mTerminateRequested || searchProcessing != searchRequested);
  }
}
