package org.ggp.base.apps.validator;

import java.util.List;

import org.ggp.base.util.game.GameRepository;
import org.ggp.base.util.game.LocalGameRepository;
import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.profile.ProfilerContext;
import org.ggp.base.util.profile.ProfilerSampleSetSimple;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

class PerfTester
{
  private int memberCount;

  void doTest()
  {
    class TestEntity
    {
      int value;

      void doInc()
      {
        value++;
      }
    }

    class DerivedTestEntity extends TestEntity
    {
      @Override
      void doInc()
      {
        value++;
      }
    }

    long startTime = System.currentTimeMillis();
    long numDepthCharges = 0;
    int localCount = 0;

    while(System.currentTimeMillis() < startTime + 10000)
    {
      localCount++;
      numDepthCharges++;
    }

    System.out.println("Local: " + (numDepthCharges/10) + " cycles per second");

    startTime = System.currentTimeMillis();
    numDepthCharges = 0;
    memberCount = 0;

    while(System.currentTimeMillis() < startTime + 10000)
    {
      memberCount++;
      numDepthCharges++;
    }

    System.out.println("Member: " + (numDepthCharges/10) + " cycles per second");

    TestEntity testEntity = new TestEntity();
    startTime = System.currentTimeMillis();
    numDepthCharges = 0;

    while(System.currentTimeMillis() < startTime + 10000)
    {
      testEntity.value++;
      numDepthCharges++;
    }

    System.out.println("Other object member: " + (numDepthCharges/10) + " cycles per second");

    startTime = System.currentTimeMillis();
    numDepthCharges = 0;
    memberCount = 0;

    while(System.currentTimeMillis() < startTime + 10000)
    {
      doInc();
      numDepthCharges++;
    }

    System.out.println("Local method: " + (numDepthCharges/10) + " cycles per second");

    testEntity = new TestEntity();
    startTime = System.currentTimeMillis();
    numDepthCharges = 0;

    while(System.currentTimeMillis() < startTime + 10000)
    {
      testEntity.doInc();
      numDepthCharges++;
    }

    System.out.println("Other object method: " + (numDepthCharges/10) + " cycles per second");

    testEntity = new DerivedTestEntity();
    startTime = System.currentTimeMillis();
    numDepthCharges = 0;

    while(System.currentTimeMillis() < startTime + 10000)
    {
      testEntity.doInc();
      numDepthCharges++;
    }

    System.out.println("Other object override method: " + (numDepthCharges/10) + " cycles per second");
  }

  private void doInc()
  {
    memberCount++;
  }
}

public class StateMachinePerformanceAnalyser
{

  public static void main(String[] args)
  {
    if ( args.length >= 1 )
    {
      GameRepository theRepository = GameRepository.getDefaultRepository();
      try
      {
        String gameKey = args[0];

        if ( theRepository.getGameKeys().contains(gameKey))
        {
          //  Instantiate the statemachine to be tested here as per the following commented out
          //  line in place of the basic prover
          //TestPropnetStateMachine theMachine = new TestPropnetStateMachine(new LearningComponentFactory());
          ForwardDeadReckonPropnetStateMachine theMachine = new ForwardDeadReckonPropnetStateMachine();

          System.out.println("Measure game " + gameKey + " state machine performance.");

          List<Gdl> description = theRepository.getGame(gameKey).getRules();
          theMachine.initialize(description);
          theMachine.disableGreedyRollouts();

          ForwardDeadReckonInternalMachineState initialState = theMachine.createInternalState(theMachine.getInitialState());
          Role ourRole = theMachine.getRoles().get(0);

          ProfilerContext.setProfiler(new ProfilerSampleSetSimple());
          try
          {
            long startTime = System.currentTimeMillis();
            int numDepthCharges = 0;

            while(System.currentTimeMillis() < startTime + 10000)
            {
              theMachine.getDepthChargeResult(initialState, null, ourRole, null, null, null);
              numDepthCharges++;
            }

            System.out.println("Performed " + (numDepthCharges/10) + " depth charges per second from initial state");
            if (ProfilerContext.getContext() != null)
            {
              System.out.println("Profile stats: \n" + ProfilerContext.getContext().toString());
            }
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
    else
    {
      (new PerfTester()).doTest();
    }
  }
}
