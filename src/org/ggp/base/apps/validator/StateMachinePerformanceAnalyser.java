package org.ggp.base.apps.validator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.ggp.base.player.gamer.statemachine.sancho.GameSearcher;
import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.player.gamer.statemachine.sancho.RuntimeGameCharacteristics;
import org.ggp.base.player.gamer.statemachine.sancho.ThreadControl;
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.CombinedHeuristic;
import org.ggp.base.player.gamer.statemachine.sancho.heuristic.Heuristic;
import org.ggp.base.util.game.GameRepository;
import org.ggp.base.util.game.LocalGameRepository;
import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * @author steve
 * Simple test app for testing aspects of Sancho (subsystem) performance
 * Currently includes tests for the state-machine and for the game search subsystem
 */
public class StateMachinePerformanceAnalyser
{
  /**
   * Number of seconds to run each test
   */
  static int numSeconds = 30;

  private class PerformanceInfo
  {
    public PerformanceInfo()
    {
    }
    public int stateMachineDirectRolloutsPerSecond;
    public int rolloutsPerSecond;
    public int expansionsPerSecond;
    public long highestLatency;
    public long averageLatency;
  }

  /**
   * @param args app commandline args, as follows:
   *  [-statemachine] - includes direct state machine rollout tests
   *  [-gamesearcher] - includes tests of the higher-level game searcher
   *  [-time<num seconds>] - specify how long to run each test for
   *  <remaining params, of arbitrary number, each of which is a game name to test>
   */
  public static void main(String[] args)
  {
    Map<String,PerformanceInfo> gamesList = new HashMap<>();
    boolean testDirectStateMachine = false;
    boolean testSanchoGameSearcher = false;

    for(String arg : args)
    {
      if ( arg.startsWith("-") )
      {
        switch(arg.toLowerCase())
        {
          case "-statemachine":
            testDirectStateMachine = true;
            break;
          case "-gamesearcher":
            testSanchoGameSearcher = true;
            break;
          default:
            if ( arg.toLowerCase().startsWith("-time"))
            {
              numSeconds = Integer.parseInt(arg.substring(5));
            }
            else
            {
              System.out.println("Parameter usage: [-statemachine] [-gamesearcher] [-time<numSeconds>] <gameName> [<gameName>...]");
              System.exit(1);
            }
        }
      }
      else
      {
        gamesList.put(arg,null);
      }
    }

    StateMachinePerformanceAnalyser analyser = new StateMachinePerformanceAnalyser();
    GameRepository theRepository = null;
    try
    {
      theRepository = GameRepository.getDefaultRepository();

      if ( testDirectStateMachine )
      {
        analyser.testDirectStateMachine(theRepository, gamesList);
      }

      if ( testSanchoGameSearcher )
      {
        analyser.testGameSearcher(theRepository, gamesList);
      }

      for(Entry<String, PerformanceInfo> e : gamesList.entrySet())
      {
        System.out.println("Game " + e.getKey() + ":");
        if ( testDirectStateMachine )
        {
          System.out.println("  Direct state machine rollouts per second: " + e.getValue().stateMachineDirectRolloutsPerSecond);
        }
        if ( testSanchoGameSearcher )
        {
          System.out.println("  GameSearcher rollouts per second: " + e.getValue().rolloutsPerSecond);
          System.out.println("  GameSearcher node expansions per second: " + e.getValue().expansionsPerSecond);
          System.out.println("  GameSearcher highest pipeline latency(micro seconds): " + e.getValue().highestLatency/1000);
          System.out.println("  GameSearcher average pipeline latency(micro seconds): " + e.getValue().averageLatency/1000);
        }
      }
    }
    finally
    {
      // The local repository suffers from a lack of releasing its port binding
      // under certain execution conditions (debug under Eclipse), so do it
      // explicitly to leave things in a clean state
      if (theRepository instanceof LocalGameRepository)
      {
        ((LocalGameRepository)theRepository).cleanUp();
      }
    }
  }

  private void testDirectStateMachine(GameRepository theRepository, Map<String,PerformanceInfo> gamesList)
  {
    for(String gameKey : gamesList.keySet())
    {
      if ( theRepository.getGameKeys().contains(gameKey))
      {
        //  Instantiate the statemachine to be tested here as per the following commented out
        //  line in place of the basic prover
        ForwardDeadReckonPropnetStateMachine theMachine = new ForwardDeadReckonPropnetStateMachine();

        System.out.println("Measure game " + gameKey + " state machine performance.");

        List<Gdl> description = theRepository.getGame(gameKey).getRules();
        theMachine.initialize(description);
        theMachine.enableGreedyRollouts(false, true);

        theMachine.optimizeStateTransitionMechanism(System.currentTimeMillis()+2000);

        ForwardDeadReckonInternalMachineState initialState = theMachine.createInternalState(theMachine.getInitialState());
        Role ourRole = theMachine.getRoles()[0];

        try
        {
          long startTime = System.currentTimeMillis();
          int numDepthCharges = 0;

          while(System.currentTimeMillis() < startTime + 1000*numSeconds)
          {
            theMachine.getDepthChargeResult(initialState, null, ourRole, null, null, null, 1000);
            numDepthCharges++;
          }

          PerformanceInfo perfInfo = gamesList.get(gameKey);
          if ( perfInfo == null )
          {
            perfInfo = new PerformanceInfo();
          }

          perfInfo.stateMachineDirectRolloutsPerSecond = numDepthCharges/numSeconds;
          gamesList.put(gameKey,perfInfo);
        }
        catch (Exception e)
        {
          GamerLogger.logStackTrace("StateMachine", e);
        }
      }
      else
      {
        System.out.println("Game " + gameKey + " not found");
        for(String key : theRepository.getGameKeys())
        {
          System.out.println(key);
        }
      }
    }
  }

  private void testGameSearcher(GameRepository theRepository, Map<String,PerformanceInfo> gamesList)
  {
    for (String gameKey : gamesList.keySet())
    {
      if (theRepository.getGameKeys().contains(gameKey))
      {
        System.out.println("Measure game " + gameKey + " state machine performance.");

        RuntimeGameCharacteristics gameCharacteristics = new RuntimeGameCharacteristics(null);
        ForwardDeadReckonPropnetStateMachine theMachine =
                                           new ForwardDeadReckonPropnetStateMachine(ThreadControl.CPU_INTENSIVE_THREADS,
                                                                                    25000,
                                                                                    null,
                                                                                    gameCharacteristics);
        List<Gdl> description = theRepository.getGame(gameKey).getRules();
        theMachine.initialize(description);
        theMachine.enableGreedyRollouts(false, true);
        gameCharacteristics.setRolloutSampleSize(1);

        GameSearcher gameSearcher = new GameSearcher(1000000, theMachine.getRoles().length, "PerfTest");

        long endTime;
        ForwardDeadReckonInternalMachineState initialState = theMachine.createInternalState(theMachine.getInitialState());
        try
        {
          Thread lSearchProcessorThread = new Thread(gameSearcher, "Search Processor");

          if (!ThreadControl.RUN_SYNCHRONOUSLY)
          {
            lSearchProcessorThread.setDaemon(true);
            lSearchProcessorThread.start();
          }

          Heuristic lHeuristic = new CombinedHeuristic();
          lHeuristic.tuningComplete();

          gameSearcher.setup(theMachine,
                             initialState,
                             new RoleOrdering(theMachine, theMachine.getRoles()[0]),
                             gameCharacteristics,
                             true,
                             lHeuristic,
                             null,
                             null);

          endTime = System.currentTimeMillis() + numSeconds*1000;
          gameSearcher.startSearch(endTime, initialState, (short)0, null);

          if (ThreadControl.RUN_SYNCHRONOUSLY)
          {
            try
            {
              while (System.currentTimeMillis() < endTime && !gameSearcher.isComplete())
              {
                gameSearcher.expandSearch(true);
              }
            }
            catch (MoveDefinitionException | TransitionDefinitionException e)
            {
              // TODO Auto-generated catch block
              e.printStackTrace();
            }
          }
          else
          {
            Thread.sleep(endTime - System.currentTimeMillis());
          }

          PerformanceInfo perfInfo = gamesList.get(gameKey);
          if ( perfInfo == null )
          {
            perfInfo = new PerformanceInfo();
          }

          perfInfo.expansionsPerSecond = gameSearcher.getNumIterations()/numSeconds;
          perfInfo.rolloutsPerSecond = gameSearcher.getNumRollouts()/numSeconds;
          perfInfo.highestLatency = gameSearcher.longestObservedLatency;
          perfInfo.averageLatency = gameSearcher.averageLatency;
          gamesList.put(gameKey,perfInfo);

          gameSearcher.terminate();
        }
        catch (GoalDefinitionException e1)
        {
          // TODO Auto-generated catch block
          e1.printStackTrace();
        }
        catch (InterruptedException e1)
        {
          // TODO Auto-generated catch block
          e1.printStackTrace();
        }
      }
      else
      {
        System.err.println("Game not found: " + gameKey);
      }
    }
  }
}
