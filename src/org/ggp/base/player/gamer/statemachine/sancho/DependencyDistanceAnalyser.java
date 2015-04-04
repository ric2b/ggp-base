package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;
import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState.InternalMachineStateIterator;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropNet;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropositionInfo;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

/**
 * @author steve
 * This class analyses a game via its propnet, to determine dependency distances between moves
 * and (future) between propositions and moves
 */
public class DependencyDistanceAnalyser
{
  private static final Logger LOGGER       = LogManager.getLogger();

  private static final int MAX_DISTANCE = 20;

  private class SentenceInfo
  {
    int[] rangeSize;

    SentenceInfo(GdlSentence sentence)
    {
      rangeSize = new int[sentence.getBody().size()];
    }
  }

  private Map<GdlConstant,SentenceInfo> sentenceInfoDictionary = new HashMap<>();

  private class MoveInfo
  {
    public MoveInfo(ForwardDeadReckonLegalMoveInfo xiLegalMoveInfo)
    {
      //legalMoveInfo = xiLegalMoveInfo;

      for(int i = 0; i < MAX_DISTANCE; i++)
      {
        //movesAtDistance[i] = new BitSet();
        basePropsEnabledAtDistanceBySameRole[i] = new BitSet();
        basePropsDisabledAtDistanceBySameRole[i] = new BitSet();
        basePropsEnabledAtDistanceByOppositeRole[i] = new BitSet();
        basePropsDisabledAtDistanceByOppositeRole[i] = new BitSet();
      }
    }

    //final BitSet[]                              movesAtDistance = new BitSet[MAX_DISTANCE];
    final BitSet[]                              basePropsEnabledAtDistanceBySameRole = new BitSet[MAX_DISTANCE];
    final BitSet[]                              basePropsDisabledAtDistanceBySameRole = new BitSet[MAX_DISTANCE];
    final BitSet[]                              basePropsEnabledAtDistanceByOppositeRole = new BitSet[MAX_DISTANCE];
    final BitSet[]                              basePropsDisabledAtDistanceByOppositeRole = new BitSet[MAX_DISTANCE];
    final BitSet                                linkedMoves = new BitSet();
    final BitSet                                enabledBaseProps = new BitSet();
    final BitSet                                disabledBaseProps = new BitSet();
    final BitSet                                requiredSetBaseProps = new BitSet();
    final BitSet                                requiredNotSetBaseProps = new BitSet();
    int                                         forRoleIndex = -1;
    //final ForwardDeadReckonLegalMoveInfo        legalMoveInfo;
  }

  private class BasePropInfo
  {
    public BasePropInfo()
    {
      for(int distance = 0; distance < MAX_DISTANCE; distance++)
      {
//        propsAtDistance[distance] = new BitSet();
//        propsAtDistanceEndingWithRolePlay[0][distance] = new BitSet();
//        propsAtDistanceEndingWithRolePlay[1][distance] = new BitSet();
        movesInfluencingFromDistance[distance] = new BitSet();
        //movesDisabledAtDistance[distance] = new BitSet();
      }
    }
//
//    final BitSet[][]  propsAtDistanceEndingWithRolePlay = new BitSet[2][MAX_DISTANCE];
//    final BitSet[]    propsAtDistance = new BitSet[MAX_DISTANCE];
//    final BitSet      linkedProps = new BitSet();
    final BitSet      potentiallyEnabledMoves = new BitSet();
    final BitSet      potentiallyDisabledMoves = new BitSet();
    final BitSet      mutualExclusionSet = new BitSet();
    final BitSet[]    movesInfluencingFromDistance = new BitSet[MAX_DISTANCE];
    //final BitSet[]    movesDisabledAtDistance = new BitSet[MAX_DISTANCE];
    //HashSet<MoveInfo> moves = null;
  }

  private final ForwardDeadReckonPropnetStateMachine stateMachine;
  private ForwardDeadReckonPropNet propNet;
  private ForwardDeadReckonLegalMoveInfo[] moveList;
  private PolymorphicProposition[] propList;
  private BasePropInfo[] basePropInfo;
  private MoveInfo[] moveDependencyInfo;


  /**
   * Constructor
   * @param xiStateMachine
   */
  public DependencyDistanceAnalyser(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    stateMachine = xiStateMachine;
  }

  /**
   * Create distance info for the game
   * The calculated distances are a lower bound, independent of state.
   * The actual distances in any particular state may exceed these values
   * @return instance of DependencyDistanceInfo
   */
  public DependencyDistanceInfo getDistanceInfo()
  {
    propNet = stateMachine.getFullPropNet();
    moveList = propNet.getMasterMoveList();
    propList = propNet.getBasePropositionsArray();
    basePropInfo = new BasePropInfo[propNet.getBasePropositionsArray().length];
    DependencyDistanceInfo result = new DependencyDistanceInfo();

    result.moveCoInfluenceDistances = new int[moveList.length][moveList.length];
    result.moveEnablingDistances = new int[moveList.length][moveList.length];

    for(int i = 0; i < moveList.length; i++)
    {
      for(int j = 0; j < moveList.length; j++)
      {
        result.moveCoInfluenceDistances[i][j] = MAX_DISTANCE;
        result.moveEnablingDistances[i][j] = MAX_DISTANCE;
      }
    }

    //  First calculate all base props within one turn distance of each move.  That
    //  is to say the set of base props whose state can be changed by the move in question
    moveDependencyInfo = new MoveInfo[moveList.length];

    for(int i = 0; i < moveList.length; i++)
    {
      //System.out.println("Move: " + moveList[i]);
      moveDependencyInfo[i] = new MoveInfo(moveList[i]);
    }

    //  Assign moves to their respective roles
    for(int roleIndex = 0; roleIndex < 2; roleIndex++)
    {
      Role role = propNet.getRoles()[roleIndex];
      for ( PolymorphicProposition legalProp : propNet.getLegalPropositions().get(role) )
      {
        moveDependencyInfo[((ForwardDeadReckonProposition)legalProp).getInfo().index].forRoleIndex = roleIndex;
      }
    }

    //  From the move info construct initial prop info detailing which moves link to each base prop

    //  Create an inverse mapping of moves affecting each base prop, ignoring control props
    //HashMap<PolymorphicProposition,HashSet<MoveInfo>> basePropMoves = new HashMap<>();
    InternalMachineStateIterator stateIterator = new InternalMachineStateIterator();
    ForwardDeadReckonInternalMachineState nonControlProps = new ForwardDeadReckonInternalMachineState(stateMachine.getControlMask());
    nonControlProps.invert();
    stateIterator.reset(nonControlProps);
    while (stateIterator.hasNext())
    {
      ForwardDeadReckonPropositionInfo propInfo = stateIterator.next();
//      HashSet<MoveInfo> moveSet = new HashSet<>();
//
//      for(int i = 0; i < moveList.length; i++)
//      {
//        MoveInfo depInfo = moveDependencyInfo[i];
//        if ( depInfo.directlyAffectedProps.get(propInfo.index))
//        {
//          moveSet.add(depInfo);
//        }
//      }

      if ( basePropInfo[propInfo.index] == null )
      {
        basePropInfo[propInfo.index] = new BasePropInfo();
      }

      //  Determine which other base props this one is mutually exclusive with
      determineMutualExclusivity(propList[propInfo.index], basePropInfo[propInfo.index].mutualExclusionSet);

      //  Determine the moves whose legals are influenced directly by this base prop
      recursiveAddImmediatelyDependentMovesViaLegals(propNet.getLegalInputMap().keySet(),
                                                     propList[propInfo.index],
                                                     basePropInfo[propInfo.index].potentiallyEnabledMoves,
                                                     basePropInfo[propInfo.index].potentiallyDisabledMoves,
                                                     true);


//      basePropInfo[propInfo.index].moves = moveSet;
//
//      //  Fill in distance 1 links to the other base props each move influences
//      for(MoveInfo moveInfo : moveSet)
//      {
//        int linkedPropIndex = 0;
//        while((linkedPropIndex = moveInfo.directlyAffectedProps.nextSetBit(linkedPropIndex)) != -1)
//        {
//          if ( linkedPropIndex != propInfo.index )
//          {
//            basePropInfo[propInfo.index].linkedProps.set(linkedPropIndex);
//            basePropInfo[propInfo.index].propsAtDistanceEndingWithRolePlay[moveInfo.forRoleIndex][1].set(linkedPropIndex);
//            basePropInfo[propInfo.index].propsAtDistance[1].set(linkedPropIndex);
//          }
//          linkedPropIndex++;
//        }
//      }
      //basePropMoves.put(baseProp, moveSet);

      //  Now set distances of 1 (different roles) or 2 (same role) for move pairs influencing this base prop
//      for(MoveInfo move1Info : moveSet)
//      {
//        for(MoveInfo move2Info : moveSet)
//        {
//          if ( move1Info != move2Info )
//          {
//            int distance = (move1Info.forRole == move2Info.forRole ? 2 : 1);
//
//            move1Info.movesAtDistance[distance].set(move2Info.legalMoveInfo.masterIndex);
//            move2Info.movesAtDistance[distance].set(move1Info.legalMoveInfo.masterIndex);
//          }
//        }
//      }
    }

    //  Work out any required and required-absent base props for each move to be legal
    for(int i = 0; i < moveList.length; i++)
    {
      boolean trace = false;
      MoveInfo depInfo = moveDependencyInfo[i];
      if ( depInfo.forRoleIndex != -1 )
      {
        PolymorphicProposition inputProp = moveList[i].inputProposition;
        PolymorphicProposition legalProp = propNet.getLegalInputMap().get(inputProp);

        if ( legalProp != null )
        {
          findRequiredBasePropStatesForLegal(legalProp, depInfo.requiredSetBaseProps, depInfo.requiredNotSetBaseProps, true);

          if ( trace )
          {
            LOGGER.info("Legality requirements for move " + moveList[i] + ":");
          }

          int index = 0;
          while((index = depInfo.requiredSetBaseProps.nextSetBit(index)) != -1)
          {
            //  If a required prop has mutual exclusions then those excluded props are required to be clear
            BasePropInfo requiredPropInfo = basePropInfo[index];
            if ( requiredPropInfo != null )
            {
              int excludedPropIndex = 0;
              while((excludedPropIndex = requiredPropInfo.mutualExclusionSet.nextSetBit(excludedPropIndex)) != -1)
              {
                depInfo.requiredNotSetBaseProps.set(excludedPropIndex);
                excludedPropIndex++;
              }
            }

            if ( trace )
            {
              LOGGER.info( "  Required set: " + propList[index].getName());
            }
            index++;
          }

          if ( trace )
          {
            index = 0;
            while((index = depInfo.requiredNotSetBaseProps.nextSetBit(index)) != -1)
            {
              LOGGER.info( "  Required clear: " + propList[index].getName());
              index++;
            }
          }
        }
      }
    }

    //  Walk the propnet to find base props within one transition of each move's input prop
    for(int i = 0; i < moveList.length; i++)
    {
      MoveInfo depInfo = moveDependencyInfo[i];
      if ( depInfo.forRoleIndex != -1 )
      {
        PolymorphicProposition inputProp = moveList[i].inputProposition;

        if ( inputProp != null )
        {
          recursiveAddImmediatelyDependentBaseProps(inputProp, depInfo.basePropsEnabledAtDistanceBySameRole[1], depInfo.basePropsDisabledAtDistanceBySameRole[1], true);

          //  Mask out any props that are required for the move to be legal and are set by the move (implies unchanged) or are
          //  required to be clear for the move tom be legal and cleared by making the move (again implies unchanged)
          depInfo.basePropsEnabledAtDistanceBySameRole[1].andNot(depInfo.requiredSetBaseProps);
          depInfo.basePropsDisabledAtDistanceBySameRole[1].andNot(depInfo.requiredNotSetBaseProps);
        }
      }
    }

    //  Now Calculate the base props influencable at increasing distance for each move
    for(int targetDistance = 1; targetDistance < MAX_DISTANCE-1; targetDistance++)
    {
      for(int i = 0; i < moveList.length; i++)
      {
        MoveInfo moveInfo = moveDependencyInfo[i];
        boolean processingOppositeMoveRole = true;

        boolean trace = false;//(moveList[i].toString().contains("2 4 1 3") || moveList[i].toString().contains("2 4 1 3"));
        if ( trace )
        {
          LOGGER.info("Tracing from props at distance " + targetDistance + " of move " + moveList[i]);
        }
        do
        {
          boolean processingEnablement = true;

          do
          {
            int seedPropIndex = 0;
            BitSet propSet;

            if ( processingEnablement )
            {
              if ( processingOppositeMoveRole )
              {
                propSet = moveInfo.basePropsEnabledAtDistanceByOppositeRole[targetDistance];
              }
              else
              {
                propSet = moveInfo.basePropsEnabledAtDistanceBySameRole[targetDistance];
              }
            }
            else
            {
              if ( processingOppositeMoveRole )
              {
                propSet = moveInfo.basePropsDisabledAtDistanceByOppositeRole[targetDistance];
              }
              else
              {
                propSet = moveInfo.basePropsDisabledAtDistanceBySameRole[targetDistance];
              }
            }
            while((seedPropIndex = propSet.nextSetBit(seedPropIndex)) != -1)
            {
              if ( trace )
              {
                LOGGER.info("  Prop " + propList[seedPropIndex].getName() + " is " + (processingEnablement ? "enabled" : "disabled"));
              }
              int relatedMoveIndex = 0;
              BitSet relatedMoveSet = (processingEnablement ? basePropInfo[seedPropIndex].potentiallyEnabledMoves : basePropInfo[seedPropIndex].potentiallyDisabledMoves);

              while((relatedMoveIndex = relatedMoveSet.nextSetBit(relatedMoveIndex)) != -1)
              {
                //  Effective distance depends on relative roles for the moves, since if same role an
                //  intervening opponent turn is required.  If we're 'processingOppositeMoveRole' that means
                //  the prop enablement/disablement occurs as the result of an opposite (relative to the root move)
                //  role move, so if that is the same role as the move its enabvling/disabling (the 'relatedMove')
                //  then the intervening turn is required
                int effectiveDistance = (((moveDependencyInfo[relatedMoveIndex].forRoleIndex != moveInfo.forRoleIndex) == processingOppositeMoveRole) ? targetDistance+2 : targetDistance+1);
                if ( effectiveDistance < MAX_DISTANCE )
                {
                  //  The related move's legality can be impacted at this distance from the root move
                  if ( result.moveEnablingDistances[i][relatedMoveIndex] > effectiveDistance-1 )
                  {
                    if ( trace )
                    {
                      LOGGER.info("    Move " + moveList[relatedMoveIndex] + " then potentially has its legality modified at distance " + (effectiveDistance-1));
                    }
                    result.moveEnablingDistances[i][relatedMoveIndex] = effectiveDistance-1;
                  }

                  if ( relatedMoveIndex != i && !moveInfo.linkedMoves.get(relatedMoveIndex) )
                  {
                    MoveInfo enabledMoveInfo = moveDependencyInfo[relatedMoveIndex];

                    if ( trace )
                    {
                      LOGGER.info("    Move " + moveList[relatedMoveIndex] + " is then potentially enabled");
                    }

                    addMoveFringeAtDistance(moveInfo, enabledMoveInfo, effectiveDistance, !processingOppositeMoveRole, trace);
                  }
                }

                relatedMoveIndex++;
              }

              //  Conversely a prop that is disabled enables the moves its setting disables
              relatedMoveIndex = 0;
              relatedMoveSet = (processingEnablement ? basePropInfo[seedPropIndex].potentiallyDisabledMoves : basePropInfo[seedPropIndex].potentiallyEnabledMoves);

              while((relatedMoveIndex = relatedMoveSet.nextSetBit(relatedMoveIndex)) != -1)
              {
                if ( relatedMoveIndex != i && !moveInfo.linkedMoves.get(relatedMoveIndex) )
                {
                  MoveInfo enabledMoveInfo = moveDependencyInfo[relatedMoveIndex];

                  if ( trace )
                  {
                    LOGGER.info("    Move " + moveList[relatedMoveIndex] + " is then potentially enabled");
                  }
                  if ( processingOppositeMoveRole )
                  {
                    //  Any props THIS move effects are added at distance +1
                    addMoveFringeAtDistance(moveInfo, enabledMoveInfo, targetDistance+1, true, trace);
                  }
                  else if ( targetDistance < MAX_DISTANCE-2 )
                  {
                    //  Any props THIS move effects are added at distance +2
                    addMoveFringeAtDistance(moveInfo, enabledMoveInfo, targetDistance+2, false, trace);
                  }
                }
                relatedMoveIndex++;
              }

              seedPropIndex++;
            }

            processingEnablement = !processingEnablement;
          } while( !processingEnablement );

          processingOppositeMoveRole = !processingOppositeMoveRole;
        } while(!processingOppositeMoveRole);
      }
    }

    //  Invert to get the moves within a given radius for each base prop
    for(int i = 0; i < moveList.length; i++)
    {
      MoveInfo moveInfo = moveDependencyInfo[i];
      boolean trace = false;//(moveList[i].toString().contains("4 2 4 3") || moveList[i].toString().contains("6 4 7 5"));
      if ( trace )
      {
        LOGGER.info("Influenceable props from move " + moveList[i]);
      }
      for(int distance = 1; distance < MAX_DISTANCE; distance++)
      {
        int basePropIndex = 0;
        while((basePropIndex = moveInfo.basePropsEnabledAtDistanceBySameRole[distance].nextSetBit(basePropIndex)) != -1)
        {
          if ( trace )
          {
            LOGGER.info("  Base prop " + propList[basePropIndex].getName() + ": " + distance);
          }
          basePropInfo[basePropIndex].movesInfluencingFromDistance[distance].set(i);
          basePropIndex++;
        }

        basePropIndex = 0;
        while((basePropIndex = moveInfo.basePropsDisabledAtDistanceBySameRole[distance].nextSetBit(basePropIndex)) != -1)
        {
          if ( trace )
          {
            LOGGER.info("  Base prop " + propList[basePropIndex].getName() + ": " + distance);
          }
          basePropInfo[basePropIndex].movesInfluencingFromDistance[distance].set(i);
          basePropIndex++;
        }

        basePropIndex = 0;
        while((basePropIndex = moveInfo.basePropsEnabledAtDistanceByOppositeRole[distance].nextSetBit(basePropIndex)) != -1)
        {
          if ( trace )
          {
            LOGGER.info("  Base prop " + propList[basePropIndex].getName() + ": " + distance);
          }
          basePropInfo[basePropIndex].movesInfluencingFromDistance[distance].set(i);
          basePropIndex++;
        }

        basePropIndex = 0;
        while((basePropIndex = moveInfo.basePropsDisabledAtDistanceByOppositeRole[distance].nextSetBit(basePropIndex)) != -1)
        {
          if ( trace )
          {
            LOGGER.info("  Base prop " + propList[basePropIndex].getName() + ": " + distance);
          }
          basePropInfo[basePropIndex].movesInfluencingFromDistance[distance].set(i);
          basePropIndex++;
        }
      }
    }

    //  We want WITHIN a distance, not AT one for so take nested unions
//    for(int i = 0; i < propList.length; i++)
//    {
//      BasePropInfo propInfo = basePropInfo[i];
//      if ( propInfo != null )
//      {
//        for(int distance = 1; distance < MAX_DISTANCE-1; distance++)
//        {
//          propInfo.movesWithinRadius[distance+1].or(propInfo.movesWithinRadius[distance]);
//        }
//      }
//    }

    //  Now calculate the final move distances
    for(int i = 0; i < moveList.length; i++)
    {
      MoveInfo moveInfo = moveDependencyInfo[i];
      boolean trace = false;//(moveList[i].toString().contains("4 2 4 3") || moveList[i].toString().contains("6 4 7 5"));
      if ( trace )
      {
        LOGGER.info("Calculating distances from: " + moveList[i]);
      }

      for(int cumulativeDistance = 2; cumulativeDistance < MAX_DISTANCE; cumulativeDistance++)
      {
        if ( cumulativeDistance > 9 )
        {
          trace = false;
        }
        if ( trace )
        {
          LOGGER.info("  Cumulative distance: " + cumulativeDistance);
        }
        //  The total distance is comprised of the number of turns to influence the commonly influenced
        //  base prop from the first move, and from the second as separate 'legs'.  We reduce the final
        //  total by 1 as we assume that one of the moves is acting as a seed and has been played
        for(int distance1 = 1; distance1 < cumulativeDistance; distance1++ )
        {
          //  Find base props at distance1 from the initial move
          int basePropIndex = 0;
          while((basePropIndex = moveInfo.basePropsEnabledAtDistanceBySameRole[distance1].nextSetBit(basePropIndex)) != -1)
          {
            int distance2 = cumulativeDistance - distance1;

            LinkMovesAtDistance(i, basePropIndex, distance1, distance2, result.moveCoInfluenceDistances, trace);
            basePropIndex++;
          }

          basePropIndex = 0;
          while((basePropIndex = moveInfo.basePropsDisabledAtDistanceBySameRole[distance1].nextSetBit(basePropIndex)) != -1)
          {
            int distance2 = cumulativeDistance - distance1;

            LinkMovesAtDistance(i, basePropIndex, distance1, distance2, result.moveCoInfluenceDistances, trace);
            basePropIndex++;
          }

          basePropIndex = 0;
          while((basePropIndex = moveInfo.basePropsEnabledAtDistanceByOppositeRole[distance1].nextSetBit(basePropIndex)) != -1)
          {
            int distance2 = cumulativeDistance - distance1;

            LinkMovesAtDistance(i, basePropIndex, distance1, distance2, result.moveCoInfluenceDistances, trace);
            basePropIndex++;
          }

          basePropIndex = 0;
          while((basePropIndex = moveInfo.basePropsDisabledAtDistanceByOppositeRole[distance1].nextSetBit(basePropIndex)) != -1)
          {
            int distance2 = cumulativeDistance - distance1;

            LinkMovesAtDistance(i, basePropIndex, distance1, distance2, result.moveCoInfluenceDistances, trace);
            basePropIndex++;
          }
        }
      }
    }

    //  Validate symmetry
    for(int i = 0; i < moveList.length; i++)
    {
      for(int j = 0; j < i; j++)
      {
        if (result.moveCoInfluenceDistances[i][j] != result.moveCoInfluenceDistances[j][i])
        {
          LOGGER.info(moveList[i].inputProposition.getName().toString() + " -> " + moveList[j].inputProposition.getName().toString() + " = " + result.moveCoInfluenceDistances[i][j]);
          LOGGER.info(moveList[j].inputProposition.getName().toString() + " -> " + moveList[i].inputProposition.getName().toString() + " = " + result.moveCoInfluenceDistances[j][i]);
        }
        assert(result.moveCoInfluenceDistances[i][j] == result.moveCoInfluenceDistances[j][i]);
      }
    }

    //  Now form relationships at increasing distance until no more are found
//    for(int targetDistance = 2; targetDistance < MAX_DISTANCE; targetDistance++)
//    {
//      for(int i = 0; i < basePropInfo.length; i++)
//      {
//        BasePropInfo propInfo = basePropInfo[i];
//
//        if ( propInfo != null )
//        {
//          //  Link by moves. Opposite role couples with 1 added, same with 2
//          //  Do opposite first to get minimal distance coupling
//          for(int roleIndex = 0; roleIndex < 2; roleIndex++)
//          {
//            int linkedPropIndex = 0;
//            while((linkedPropIndex = propInfo.propsAtDistanceEndingWithRolePlay[roleIndex][targetDistance - 1].nextSetBit(linkedPropIndex)) != -1)
//            {
//              BasePropInfo prop2Info = basePropInfo[linkedPropIndex];
//
//              for(MoveInfo move : prop2Info.moves)
//              {
//                if ( move.forRoleIndex != roleIndex )
//                {
//                  int fringePropIndex = 0;
//                  while((fringePropIndex = move.directlyAffectedProps.nextSetBit(fringePropIndex)) != -1)
//                  {
//                    //  If we don't already have this prop at a lower distance add it at the target distance
//                    if ( !propInfo.linkedProps.get(fringePropIndex))
//                    {
//                      propInfo.linkedProps.set(fringePropIndex);
//                      propInfo.propsAtDistanceEndingWithRolePlay[1-roleIndex][targetDistance].set(fringePropIndex);
//                      propInfo.propsAtDistance[targetDistance].set(fringePropIndex);
//                    }
//                    fringePropIndex++;;
//                  }
//                }
//              }
//              linkedPropIndex++;
//            }
//          }
//          //  Now same-role coupling
//          if ( targetDistance > 2 )
//          {
//            for(int roleIndex = 0; roleIndex < 2; roleIndex++)
//            {
//              int linkedPropIndex = 0;
//              while((linkedPropIndex = propInfo.propsAtDistanceEndingWithRolePlay[roleIndex][targetDistance - 2].nextSetBit(linkedPropIndex)) != -1)
//              {
//                BasePropInfo prop2Info = basePropInfo[linkedPropIndex];
//
//                for(MoveInfo move : prop2Info.moves)
//                {
//                  if ( move.forRoleIndex == roleIndex )
//                  {
//                    int fringePropIndex = 0;
//                    while((fringePropIndex = move.directlyAffectedProps.nextSetBit(fringePropIndex)) != -1)
//                    {
//                      //  If we don't already have this prop at a lower distance add it at the target distance
//                      if ( !propInfo.linkedProps.get(fringePropIndex))
//                      {
//                        propInfo.linkedProps.set(fringePropIndex);
//                        propInfo.propsAtDistanceEndingWithRolePlay[1-roleIndex][targetDistance].set(fringePropIndex);
//                        propInfo.propsAtDistance[targetDistance].set(fringePropIndex);
//                      }
//                      fringePropIndex++;;
//                    }
//                  }
//                }
//                linkedPropIndex++;
//              }
//            }
//          }
//        }
//      }
//    }

//    //  Now form relationships at increasing distance until no more are found
//    for(int targetDistance = 2; targetDistance < MAX_DISTANCE; targetDistance++)
//    {
//      for(int i = 0; i < moveList.length; i++)
//      {
//        MoveInfo moveInfo = moveDependencyInfo[i];
//
//        //  Basic interactions are always at distance 1 or 2 so to fill distance N we need to look at
//        //  moves that are a further 1 on from N-2 or 2 on from N-3 - the extar 1 being due to the
//        //  fact that the intermediary coupling move takes a turn to play
//        for(int currentDistance = targetDistance-3; currentDistance < targetDistance-1; currentDistance++)
//        {
//          if ( currentDistance < 1 )
//          {
//            continue;
//          }
//
//          int fringeMoveIndex = 0;
//          while((fringeMoveIndex = moveInfo.movesAtDistance[currentDistance].nextSetBit(fringeMoveIndex)) != -1)
//          {
//            MoveInfo fringeMoveInfo = moveDependencyInfo[fringeMoveIndex];
//
//            int moveIndex = 0;
//            while((moveIndex = fringeMoveInfo.movesAtDistance[targetDistance - currentDistance].nextSetBit(moveIndex)) != -1)
//            {
//              MoveInfo move2Info = moveDependencyInfo[moveIndex];
//
//              moveInfo.movesAtDistance[targetDistance].set(moveIndex);
//              move2Info.movesAtDistance[targetDistance].set(i);
//              moveIndex++;
//            }
//
//            fringeMoveIndex++;
//          }
//        }
//      }
//    }

//    //  Check we got symmetric results
//    for(int i = 0; i < propList.length; i++)
//    {
//      BasePropInfo propInfo = basePropInfo[i];
//
//      if ( propInfo != null )
//      {
//        for(int distance = 1; distance < MAX_DISTANCE; distance++)
//        {
//          int propIndex = 0;
//          while((propIndex = propInfo.propsAtDistance[distance].nextSetBit(propIndex)) != -1)
//          {
//            BasePropInfo prop2Info = basePropInfo[propIndex];
//
//            assert(prop2Info.propsAtDistance[distance].get(i));
//
//            propIndex++;
//          }
//        }
//      }
//    }
//
//    //  Now fill in the final matrix - since this is symmetric we just do half and generate the
//    //  other half by reflection
//    for(int i = 0; i < moveList.length; i++)
//    {
//      MoveInfo moveInfo = moveDependencyInfo[i];
//
//      int propIndex = 0;
//      while((propIndex = moveInfo.directlyAffectedProps.nextSetBit(propIndex)) != -1)
//      {
//        BasePropInfo propInfo = basePropInfo[propIndex];
//
//        for(int distance = 1; distance < MAX_DISTANCE; distance++)
//        {
//          int linkedPropIndex = 0;
//          while((linkedPropIndex = propInfo.propsAtDistance[distance].nextSetBit(linkedPropIndex)) != -1)
//          {
//            for(MoveInfo linkedMove : basePropInfo[linkedPropIndex].moves)
//            {
//              int moveDistance;
//
//              if ( linkedMove.forRoleIndex == moveInfo.forRoleIndex )
//              {
//                moveDistance = distance + 2;
//              }
//              else
//              {
//                moveDistance = distance + 1;
//              }
//
//              if ( distances[i][linkedMove.legalMoveInfo.masterIndex] > moveDistance )
//              {
//                distances[i][linkedMove.legalMoveInfo.masterIndex] = moveDistance;
//              }
//            }
//            linkedPropIndex++;
//          }
//        }
//
//        propIndex++;
//      }
//    }

    return result;
  }

  private void LinkMovesAtDistance(int rootMoveIndex, int linkingPropIndex, int distance1, int distance2, int[][] distances, boolean trace)
  {
    MoveInfo moveInfo = moveDependencyInfo[rootMoveIndex];
    //  All moves at distance2 from this base prop we have not already linked
    //  to this move are no further than this cumulative distance from the original move
    int linkedMoveIndex = 0;
    while((linkedMoveIndex = basePropInfo[linkingPropIndex].movesInfluencingFromDistance[distance2].nextSetBit(linkedMoveIndex)) != -1)
    {
      //  TODO - can omit half of these by symmetry???
      if ( linkedMoveIndex != rootMoveIndex && !moveInfo.linkedMoves.get(linkedMoveIndex) )
      {
        moveInfo.linkedMoves.set(linkedMoveIndex);
        //moveInfo.movesAtDistance[distance].set(linkedMoveIndex);

        //  -1 since original move assumed played seed, +1 if same role since there has to be an intervening
        //  turn in a non-simultaneous game
        int moveDistance = distance1 + distance2 - (moveInfo.forRoleIndex == moveDependencyInfo[linkedMoveIndex].forRoleIndex ? 0 : 1);
        distances[rootMoveIndex][linkedMoveIndex] = moveDistance;

        if ( trace )
        {
          LOGGER.info("    links to " + moveList[linkedMoveIndex] + " at distance " + moveDistance + " via " + propList[linkingPropIndex].getName() + " with split (" + distance1 + ", " + distance2 + ")");
        }
      }
      linkedMoveIndex++;
    }
  }

  private void addMoveFringeAtDistance(MoveInfo rootMoveInfo, MoveInfo enabledMoveInfo, int targetDistance, boolean sameRole, boolean trace)
  {
    boolean processingFringeEnablement = true;

    do
    {
      int newlyIncludedPropIndex = 0;
      BitSet newlyIncludedPropSet = (processingFringeEnablement ? enabledMoveInfo.basePropsEnabledAtDistanceBySameRole[1] : enabledMoveInfo.basePropsDisabledAtDistanceBySameRole[1]);

      while((newlyIncludedPropIndex = newlyIncludedPropSet.nextSetBit(newlyIncludedPropIndex)) != -1)
      {
        if ( (processingFringeEnablement && !rootMoveInfo.enabledBaseProps.get(newlyIncludedPropIndex)) ||
             (!processingFringeEnablement && !rootMoveInfo.disabledBaseProps.get(newlyIncludedPropIndex)) )
        {
          if ( processingFringeEnablement )
          {
            if ( sameRole )
            {
              rootMoveInfo.basePropsEnabledAtDistanceBySameRole[targetDistance].set(newlyIncludedPropIndex);
            }
            else
            {
              rootMoveInfo.basePropsEnabledAtDistanceByOppositeRole[targetDistance].set(newlyIncludedPropIndex);
            }
            rootMoveInfo.enabledBaseProps.set(newlyIncludedPropIndex);

            if ( trace)
            {
              LOGGER.info("    Distance to enabling " + propList[newlyIncludedPropIndex].getName() + " is " + targetDistance);
            }
          }
          else
          {
            if ( sameRole )
            {
              rootMoveInfo.basePropsDisabledAtDistanceBySameRole[targetDistance].set(newlyIncludedPropIndex);
            }
            else
            {
              rootMoveInfo.basePropsDisabledAtDistanceByOppositeRole[targetDistance].set(newlyIncludedPropIndex);
            }

            rootMoveInfo.disabledBaseProps.set(newlyIncludedPropIndex);

            if ( trace)
            {
              LOGGER.info("    Distance to disabling " + propList[newlyIncludedPropIndex].getName() + " is " + targetDistance);
            }
          }
        }

        newlyIncludedPropIndex++;
      }

      processingFringeEnablement = !processingFringeEnablement;
    } while( !processingFringeEnablement );
  }

  private static void recursiveAddImmediatelyDependentBaseProps(PolymorphicComponent c, BitSet enabledSet, BitSet disabledSet, boolean sense)
  {
    if ( c instanceof PolymorphicTransition )
    {
      PolymorphicComponent baseProp = c.getSingleOutput();

      assert(baseProp instanceof PolymorphicProposition);

      if ( sense )
      {
        enabledSet.set(((ForwardDeadReckonProposition)baseProp).getInfo().index);
      }
      else
      {
        disabledSet.set(((ForwardDeadReckonProposition)baseProp).getInfo().index);
      }

      return;
    }
    else if ( c instanceof PolymorphicNot )
    {
      sense = !sense;
    }

    for(PolymorphicComponent output : c.getOutputs())
    {
      recursiveAddImmediatelyDependentBaseProps(output, enabledSet, disabledSet, sense);
    }
  }

  private static void recursiveAddImmediatelyDependentMovesViaLegals(Set<PolymorphicProposition> legals, PolymorphicComponent c, BitSet enabledSet, BitSet disabledSet, boolean sense)
  {
    if ( c instanceof PolymorphicTransition )
    {
      return;
    }
    if ( c instanceof PolymorphicNot )
    {
      sense = !sense;
    }
    else if ( c instanceof PolymorphicProposition && legals.contains(c) )
    {
      ForwardDeadReckonProposition legalProp = (ForwardDeadReckonProposition)c;

      if ( sense )
      {
        enabledSet.set(legalProp.getInfo().index);
      }
      else
      {
        disabledSet.set(legalProp.getInfo().index);
      }

      return;
    }

    for(PolymorphicComponent output : c.getOutputs())
    {
      recursiveAddImmediatelyDependentMovesViaLegals(legals, output, enabledSet, disabledSet, sense);
    }
  }

  private void findRequiredBasePropStatesForLegal(PolymorphicComponent c, BitSet requiredSet, BitSet requiredNotSet, boolean sense)
  {
    if ( c instanceof PolymorphicAnd )
    {
      if ( !sense )
      {
        return;
      }
    }
    else if ( c instanceof PolymorphicOr )
    {
      if ( sense )
      {
        return;
      }
    }
    else if ( c instanceof PolymorphicNot )
    {
      sense = !sense;
    }
    else if ( c instanceof PolymorphicProposition && propNet.getLegalInputMap().get(c) == null )
    {
      if ( sense )
      {
        requiredSet.set(((ForwardDeadReckonProposition)c).getInfo().index);
      }
      else
      {
        requiredNotSet.set(((ForwardDeadReckonProposition)c).getInfo().index);
      }

      return;
    }

    for(PolymorphicComponent input : c.getInputs())
    {
      findRequiredBasePropStatesForLegal(input, requiredSet, requiredNotSet, sense);
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void determineMutualExclusivity(PolymorphicProposition p, BitSet exclusionSet)
  {
    boolean trace = false;

    //  For now a hack!  Look for other props with different values in a term with range <= numRoles+1
    //  TODO - make this a rigorous analysis
    GdlSentence sentence = p.getName().getBody().get(0).toSentence();

    SentenceInfo info = sentenceInfoDictionary.get(sentence.getName());
    if ( info == null )
    {
      info = new SentenceInfo(sentence);
      HashSet[] termRanges = new HashSet[sentence.getBody().size()];
      for(int termIndex = 0; termIndex < sentence.getBody().size(); termIndex++)
      {
        termRanges[termIndex] = new HashSet();
      }

      for(int i = 0; i < propList.length; i++)
      {
        GdlSentence propSentence = propList[i].getName().getBody().get(0).toSentence();
        if ( propSentence.getName() == sentence.getName() )
        {
          for(int termIndex = 0; termIndex < sentence.getBody().size(); termIndex++)
          {
            termRanges[termIndex].add(propSentence.getBody().get(termIndex).toString());
          }
        }
      }

      for(int termIndex = 0; termIndex < sentence.getBody().size(); termIndex++)
      {
        info.rangeSize[termIndex] = termRanges[termIndex].size();
      }

      sentenceInfoDictionary.put(sentence.getName(), info);
    }

    if ( trace )
    {
      LOGGER.info("base prop " + p.getName() + " is mutally exclusive with:");
    }

    for(int termIndex = 0; termIndex < sentence.getBody().size(); termIndex++)
    {
      if ( info.rangeSize[termIndex] <= propNet.getRoles().length + 1 )
      {
        for(int i = 0; i < propList.length; i++)
        {
          GdlSentence propSentence = propList[i].getName().getBody().get(0).toSentence();

          if ( propSentence.getName() == sentence.getName() &&
               propSentence.getBody().get(termIndex).toString() != sentence.getBody().get(termIndex).toString())
          {
            boolean matchesOnOtherTerms = true;
            for(int otherTermIndex = 0; otherTermIndex < sentence.getBody().size(); otherTermIndex++)
            {
              if ( otherTermIndex != termIndex &&
                  propSentence.getBody().get(otherTermIndex).toString() != sentence.getBody().get(otherTermIndex).toString() )
              {
                matchesOnOtherTerms = false;
                break;
              }
            }

            if ( matchesOnOtherTerms )
            {
              exclusionSet.set(i);

              if ( trace )
              {
                LOGGER.info("  " + propSentence);
              }
            }
          }
        }
      }
    }
  }
}
