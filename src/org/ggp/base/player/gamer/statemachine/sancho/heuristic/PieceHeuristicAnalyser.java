
package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.ggp.base.util.gdl.grammar.GdlFunction;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.implementation.propnet.TestForwardDeadReckonPropnetStateMachine;

public class PieceHeuristicAnalyser extends Analyser
{
  private int                                     numRoles            = 0;
  private ForwardDeadReckonInternalMachineState[] pieceSets           = null;
  private int                                     totalSimulatedTurns = 0;

  private class GdlFunctionInfo
  {
    private String           name;
    public List<Set<String>> paramRanges;

    public GdlFunctionInfo(String name, int arity)
    {
      this.name = name;
      paramRanges = new ArrayList<Set<String>>();

      for (int i = 0; i < arity; i++)
      {
        paramRanges.add(new HashSet<String>());
      }
    }

    public String getName()
    {
      return name;
    }
  }

  private class PotentialPiecePropSet
  {
    String fnName;
    int    potentialRoleArgIndex;

    public PotentialPiecePropSet(String name, int index)
    {
      fnName = name;
      potentialRoleArgIndex = index;
    }
  }

  private final int                                                      minPiecePropArity       = 3;    //	Assume board of at least 2 dimensions + piece type
  private final int                                                      maxPiecePropArity       = 4;    //	For now (until we can do game-specific learning) restrict to exactly 2-d boards
  private final int                                                      minPiecesThreshold      = 6;
  private final double                                                   minHeuristicCorrelation = 0.075;
  private Map<ForwardDeadReckonInternalMachineState, HeuristicScoreInfo> propGroupScoreSets      = null;

  @Override
  public void init(TestForwardDeadReckonPropnetStateMachine stateMachine)
  {
    numRoles = stateMachine.getRoles().size();
    Map<String, GdlFunctionInfo> basePropFns = new HashMap<String, GdlFunctionInfo>();

    for (GdlSentence baseProp : stateMachine.getBasePropositions())
    {
      if (baseProp.arity() == 1 &&
          baseProp.getBody().get(0) instanceof GdlFunction)
      {
        GdlFunction propFn = (GdlFunction)baseProp.getBody().get(0);

        if (propFn.arity() >= minPiecePropArity &&
            propFn.arity() <= maxPiecePropArity)
        {
          GdlFunctionInfo fnInfo;

          if (basePropFns.containsKey(propFn.getName().toString()))
          {
            fnInfo = basePropFns.get(propFn.getName().toString());
          }
          else
          {
            fnInfo = new GdlFunctionInfo(propFn.getName().toString(),
                                         propFn.arity());
            basePropFns.put(propFn.getName().toString(), fnInfo);
          }

          for (int i = 0; i < propFn.arity(); i++)
          {
            fnInfo.paramRanges.get(i).add(propFn.getBody().get(i).toString());
          }
        }
      }
    }

    Set<PotentialPiecePropSet> potentialPiecePropSets = new HashSet<PotentialPiecePropSet>();

    for (GdlFunctionInfo fnInfo : basePropFns.values())
    {
      int smallestFit = Integer.MAX_VALUE;
      int smallestFitIndex = -1;

      //	Look for ranges that have cardinality of a multiple of the number of roles
      for (int i = 0; i < fnInfo.paramRanges.size(); i++)
      {
        int numValues = fnInfo.paramRanges.get(i).size();

        if (numValues < smallestFit &&
            (numValues % numRoles == 0 || numValues % numRoles == 1))
        {
          smallestFit = numValues;
          smallestFitIndex = i;
        }
      }

      if (smallestFitIndex != -1)
      {
        potentialPiecePropSets
            .add(new PotentialPiecePropSet(fnInfo.getName(), smallestFitIndex));
      }
    }

    propGroupScoreSets = new HashMap<ForwardDeadReckonInternalMachineState, HeuristicScoreInfo>();

    for (PotentialPiecePropSet pieceSet : potentialPiecePropSets)
    {
      GdlFunctionInfo info = basePropFns.get(pieceSet.fnName);

      for (String roleArgValue : info.paramRanges
          .get(pieceSet.potentialRoleArgIndex))
      {
        Set<GdlSentence> pieceSetSentences = new HashSet<GdlSentence>();

        for (GdlSentence baseProp : stateMachine.getBasePropositions())
        {
          if (baseProp.arity() == 1 &&
              baseProp.getBody().get(0) instanceof GdlFunction)
          {
            GdlFunction propFn = (GdlFunction)baseProp.getBody().get(0);

            if (propFn.arity() >= minPiecePropArity &&
                propFn.arity() <= maxPiecePropArity &&
                pieceSet.fnName.equals(propFn.getName().toString()) &&
                propFn.getBody().get(pieceSet.potentialRoleArgIndex)
                    .toString().equals(roleArgValue))
            {
              pieceSetSentences.add(baseProp);
            }
          }
        }

        if (pieceSetSentences.size() >= minPiecesThreshold)
        {
          System.out.println("Possible piece set: " + pieceSetSentences);

          ForwardDeadReckonInternalMachineState pieceMask = stateMachine
              .createInternalState(new MachineState(pieceSetSentences));

          propGroupScoreSets.put(pieceMask, new HeuristicScoreInfo(numRoles));
        }
      }
    }
  }

  @Override
  public void accrueInterimStateSample(ForwardDeadReckonInternalMachineState sampleState,
                                       int choosingRoleIndex)
  {
    for (Entry<ForwardDeadReckonInternalMachineState, HeuristicScoreInfo> e : propGroupScoreSets
        .entrySet())
    {
      int currentValue = e.getKey().intersectionSize(sampleState);

      HeuristicScoreInfo heuristicInfo = e.getValue();
      if (heuristicInfo.lastValue != -1)
      {
        if (heuristicInfo.lastValue == currentValue)
        {
          heuristicInfo.noChangeTurnRate++;
        }
        else if (choosingRoleIndex != -1)
        {
          heuristicInfo.hasRoleChanges[choosingRoleIndex] = true;
        }
        else
        {
          //	In a simultaneous turn game all roles choose
          for (int i = 0; i < numRoles; i++)
          {
            heuristicInfo.hasRoleChanges[i] = true;
          }
        }
      }

      heuristicInfo.lastValue = currentValue;
    }

    totalSimulatedTurns++;
  }

  @Override
  public void accrueTerminalStateSample(ForwardDeadReckonInternalMachineState finalState,
                                        double[] roleScores)
  {
    for (Entry<ForwardDeadReckonInternalMachineState, HeuristicScoreInfo> e : propGroupScoreSets
        .entrySet())
    {
      double heuristicScore = finalState.intersectionSize(e.getKey());

      e.getValue().accrueSample(heuristicScore, roleScores);
    }
  }

  @Override
  public void completeAnalysis()
  {
    for (Entry<ForwardDeadReckonInternalMachineState, HeuristicScoreInfo> e : propGroupScoreSets
        .entrySet())
    {
      HeuristicScoreInfo heuristicInfo = e.getValue();

      heuristicInfo.noChangeTurnRate /= totalSimulatedTurns;
    }

    for (Entry<ForwardDeadReckonInternalMachineState, HeuristicScoreInfo> e : propGroupScoreSets
        .entrySet())
    {
      if (e.getValue().noChangeTurnRate < 0.5)
      {
        System.out
            .println("Eliminating potential piece set with no-change rate: " +
                     e.getValue().noChangeTurnRate + ": " + e.getKey());
      }
      else
      {
        double[] roleCorrelations = e.getValue().getRoleCorrelations();

        System.out.println("Correlations for piece set: " + e.getKey());
        for (int i = 0; i < numRoles; i++)
        {
          System.out.println("  Role " + i + ": " + roleCorrelations[i]);

          if (roleCorrelations[i] >= minHeuristicCorrelation)
          {
            if (!e.getValue().hasRoleChanges[i])
            {
              System.out
                  .println("Eliminating potential piece set with no role decision changes for correlated role: " +
                           e.getKey());
            }
            else
            {
              if (pieceSets == null)
              {
                pieceSets = new ForwardDeadReckonInternalMachineState[numRoles];
              }

              if (pieceSets[i] == null)
              {
                pieceSets[i] = new ForwardDeadReckonInternalMachineState(e.getKey());
              }
              else
              {
                pieceSets[i].merge(e.getKey());
              }
            }
          }
        }
      }
    }

    if (pieceSets != null)
    {
      for (int i = 0; i < numRoles; i++)
      {
        if (pieceSets[i] == null)
        {
          System.out
              .println("Heuristics only identified for a subset of roles - disabling");
          pieceSets = null;
          break;
        }
      }
    }
  }

  public ForwardDeadReckonInternalMachineState[] getPieceSets()
  {
    return pieceSets;
  }
}
