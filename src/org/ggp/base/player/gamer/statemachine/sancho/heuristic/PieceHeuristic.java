
package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.player.gamer.statemachine.sancho.TreeNode;
import org.ggp.base.util.gdl.grammar.GdlFunction;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;
import org.ggp.base.util.stats.PearsonCorrelation;

/**
 * Heuristic which assumes that it's better to have more pieces than few.
 */
public class PieceHeuristic implements Heuristic
{
  private static final int                                               MIN_PIECE_PROP_ARITY      = 3;    // Assume board of at least 2 dimensions + piece type
  private static final int                                               MAX_PIECE_PROP_ARITY      = 4;    // For now (until we can do game-specific learning) restrict to exactly 2-d boards
  private static final int                                               MIN_PIECES_THRESHOLD      = 6;
  private static final double                                            MIN_HEURISTIC_CORRELATION = 0.09;

  private Map<ForwardDeadReckonInternalMachineState, HeuristicScoreInfo> propGroupScoreSets        = null;
  private int                                                            numRoles                  = 0;
  private ForwardDeadReckonInternalMachineState[]                        pieceSets                 = null;
  private int                                                            totalSimulatedTurns       = 0;
  //  The following track runtime usage state and are dependent on the current game-state
  private TreeNode                                                       rootNode                  = null;
  private int                                                            heuristicSampleWeight     = 10;
  private double[]                                                       heuristicStateValueBuffer = null;
  private int[]                                                          rootPieceCounts           = null;
  private boolean                                                        mTuningComplete           = false;

  private static class GdlFunctionInfo
  {
    private String           name;
    public List<Set<String>> paramRanges;

    public GdlFunctionInfo(String name, int arity)
    {
      this.name = name;
      paramRanges = new ArrayList<>();

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

  private static class PotentialPiecePropSet
  {
    String fnName;
    int    potentialRoleArgIndex;

    public PotentialPiecePropSet(String name, int index)
    {
      fnName = name;
      potentialRoleArgIndex = index;
    }
  }

  private static class HeuristicScoreInfo
  {
    private PearsonCorrelation[] roleCorrelation;
    int                          lastValue        = -1;
    boolean[]                    hasRoleChanges;
    double                       noChangeTurnRate = 0;

    public HeuristicScoreInfo(int numRoles)
    {
      roleCorrelation = new PearsonCorrelation[numRoles];
      hasRoleChanges = new boolean[numRoles];
      for (int i = 0; i < numRoles; i++)
      {
        roleCorrelation[i] = new PearsonCorrelation();
      }
    }

    public void accrueSample(int value, int[] roleValues)
    {
      for (int i = 0; i < roleCorrelation.length; i++)
      {
        roleCorrelation[i].sample(value, roleValues[i]);
      }
    }

    public double[] getRoleCorrelations()
    {
      double[] result = new double[roleCorrelation.length];

      for (int i = 0; i < roleCorrelation.length; i++)
      {
        result[i] = roleCorrelation[i].getCorrelation();
      }

      return result;
    }
  }

  public PieceHeuristic()
  {
  }

  private PieceHeuristic(PieceHeuristic copyFrom)
  {
    propGroupScoreSets        = copyFrom.propGroupScoreSets;
    numRoles                  = copyFrom.numRoles;
    pieceSets                 = copyFrom.pieceSets;
    totalSimulatedTurns       = copyFrom.totalSimulatedTurns;
    //  The following track runtime usage state and are dependent on the current game-state
    heuristicStateValueBuffer = new double[numRoles];
    rootPieceCounts           = new int[numRoles];
  }

  @Override
  public void tuningInitialise(ForwardDeadReckonPropnetStateMachine stateMachine,
                               RoleOrdering xiRoleOrdering)
  {
    pieceSets = null;
    numRoles = stateMachine.getRoles().size();
    heuristicStateValueBuffer = new double[numRoles];
    rootPieceCounts = new int[numRoles];

    Map<String, GdlFunctionInfo> basePropFns = new HashMap<>();

    for (GdlSentence baseProp : stateMachine.getBasePropositions())
    {
      if (baseProp.arity() == 1 &&
          baseProp.getBody().get(0) instanceof GdlFunction)
      {
        GdlFunction propFn = (GdlFunction)baseProp.getBody().get(0);

        if (propFn.arity() >= MIN_PIECE_PROP_ARITY &&
            propFn.arity() <= MAX_PIECE_PROP_ARITY)
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

    Set<PotentialPiecePropSet> potentialPiecePropSets = new HashSet<>();

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

    propGroupScoreSets = new HashMap<>();

    for (PotentialPiecePropSet pieceSet : potentialPiecePropSets)
    {
      GdlFunctionInfo info = basePropFns.get(pieceSet.fnName);

      for (String roleArgValue : info.paramRanges
          .get(pieceSet.potentialRoleArgIndex))
      {
        Set<GdlSentence> pieceSetSentences = new HashSet<>();

        for (GdlSentence baseProp : stateMachine.getBasePropositions())
        {
          if (baseProp.arity() == 1 &&
              baseProp.getBody().get(0) instanceof GdlFunction)
          {
            GdlFunction propFn = (GdlFunction)baseProp.getBody().get(0);

            if (propFn.arity() >= MIN_PIECE_PROP_ARITY &&
                propFn.arity() <= MAX_PIECE_PROP_ARITY &&
                pieceSet.fnName.equals(propFn.getName().toString()) &&
                propFn.getBody().get(pieceSet.potentialRoleArgIndex)
                    .toString().equals(roleArgValue))
            {
              pieceSetSentences.add(baseProp);
            }
          }
        }

        if (pieceSetSentences.size() >= MIN_PIECES_THRESHOLD)
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
  public void tuningInterimStateSample(ForwardDeadReckonInternalMachineState sampleState,
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
  public void tuningTerminalStateSample(ForwardDeadReckonInternalMachineState finalState,
                                        int[] roleScores)
  {
    for (Entry<ForwardDeadReckonInternalMachineState, HeuristicScoreInfo> e : propGroupScoreSets.entrySet())
    {
      int heuristicScore = finalState.intersectionSize(e.getKey());
      e.getValue().accrueSample(heuristicScore, roleScores);
    }
  }

  @Override
  public void tuningComplete()
  {
    for (Entry<ForwardDeadReckonInternalMachineState, HeuristicScoreInfo> e : propGroupScoreSets.entrySet())
    {
      HeuristicScoreInfo heuristicInfo = e.getValue();

      heuristicInfo.noChangeTurnRate /= totalSimulatedTurns;
      mTuningComplete = true;
    }

    for (Entry<ForwardDeadReckonInternalMachineState, HeuristicScoreInfo> e : propGroupScoreSets.entrySet())
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

          if (roleCorrelations[i] >= MIN_HEURISTIC_CORRELATION)
          {
            if (!e.getValue().hasRoleChanges[i])
            {
              System.out
                  .println("Eliminating potential piece set with no role decision changes for correlated role: " +
                           e.getKey());
            }
            else
            {
              System.out.println("Using piece set for role");
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
          else
          {
            System.out.println("Piece set insufficiently correlated for role");
          }
        }
      }
    }

    // Check that all roles have a set of pieces.  If not, disable the heuristic.
    if (pieceSets != null)
    {
      System.out.println("Some roles have piece sets");
      for (int i = 0; i < numRoles; i++)
      {
        System.out.println("  Checking role " + i);
        if (pieceSets[i] == null)
        {
          System.out.println("      No piece set for this role");
          System.out.println("Heuristics only identified for a subset of roles - disabling");
          pieceSets = null;
          break;
        }
        System.out.println("    Final piece set is: " + pieceSets[i]);
      }

      if (pieceSets != null)
      {
        System.out.println("All roles have piece sets");
      }
    }
  }

  @Override
  public double[] getHeuristicValue(ForwardDeadReckonInternalMachineState state,
                                    ForwardDeadReckonInternalMachineState previousState)
  {
    double total = 0;
    double rootTotal = 0;

    for (int i = 0; i < numRoles; i++)
    {
      // Set the initial heuristic value for this role according to the difference in number of pieces between this
      // state and the current state in the tree root.
      int numPieces = pieceSets[i].intersectionSize(state);
      heuristicStateValueBuffer[i] = numPieces - rootPieceCounts[i];

      total += numPieces;
      rootTotal += rootPieceCounts[i];

      // Counter-weight exchange sequences slightly to remove the first-capture bias, at least to first order.
      int previousNumPieces = pieceSets[i].intersectionSize(previousState);
      if (numPieces == rootPieceCounts[i] &&
          previousNumPieces < rootPieceCounts[i])
      {
        heuristicStateValueBuffer[i] += 0.1;
        total += 0.1;
      }
      else if (numPieces == rootPieceCounts[i] &&
               previousNumPieces > rootPieceCounts[i])
      {
        heuristicStateValueBuffer[i] -= 0.1;
        total -= 0.1;
      }
    }

    for (int i = 0; i < numRoles; i++)
    {
      if (rootTotal != total)
      {
        // There has been an overall change in the number of pieces.  Calculate the proportion of that total gained/lost
        // by this role and use that to generate a new average heuristic value for the role.
        double proportion = (heuristicStateValueBuffer[i] - (total - rootTotal) /
                                                            numRoles) /
                            (total / numRoles);
        heuristicStateValueBuffer[i] = 100 / (1 + Math.exp(-proportion * 10));
      }
      else
      {
        // There has been no overall change to the number of pieces.  Assume an average value.
        // !! ARR Why?
        heuristicStateValueBuffer[i] = 50;
      }

      // Normalize against the root score since this is relative to the root state material balance.  Only do this if
      // the root has had enough visits to have a credible estimate.
      if (rootNode.numVisits > 50)
      {
        // Set the average score for the child to the average score of the root displaced towards the extremities
        // (0/100) by a proportion of the amount that it currently deviates from the extremities, where that proportion
        // is equal to the proportion by which the heuristic value deviates from the centre.
        //
        // This assumes that the root's score is a very good basis as the initial estimate for this child node and is
        // certainly better than the heuristic value alone.
        if (heuristicStateValueBuffer[i] > 50)
        {
          heuristicStateValueBuffer[i] = rootNode.averageScores[i] +
                                         (100 - rootNode.averageScores[i]) *
                                         (heuristicStateValueBuffer[i] - 50) /
                                         50;
        }
        else
        {
          heuristicStateValueBuffer[i] = rootNode.averageScores[i] -
                                         (rootNode.averageScores[i]) *
                                         (50 - heuristicStateValueBuffer[i]) /
                                         50;
        }
      }
    }


    return heuristicStateValueBuffer;
  }

  @Override
  public int getSampleWeight()
  {
    return heuristicSampleWeight;
  }

  @Override
  public void newTurn(ForwardDeadReckonInternalMachineState xiState, TreeNode xiNode)
  {
    rootNode = xiNode;

    if (pieceSets != null)
    {
      double total = 0;
      double ourPieceCount = 0;

      for (int i = 0; i < numRoles; i++)
      {
        rootPieceCounts[i] = pieceSets[i].intersectionSize(xiState);
        total += rootPieceCounts[i];

        if (i == 0)
        {
          ourPieceCount = total;
        }
      }

      double ourMaterialDivergence = ourPieceCount - (total / numRoles);

      //  Weight further material gain down the more we're already ahead/behind in material
      //  because in either circumstance it's likely to be position that is more important
      heuristicSampleWeight = (int)Math.max(2, 6 - Math.abs(ourMaterialDivergence) * 3);
    }
    else
    {
      heuristicSampleWeight = 0;
    }
  }

  @Override
  public boolean isEnabled()
  {
    // We're enabled by default, unless we fail to discover piece sets by the end of tuning.
    return ((!mTuningComplete) || (pieceSets != null));
  }

  @Override
  public Heuristic createIndependentInstance()
  {
    return new PieceHeuristic(this);
  }
}
