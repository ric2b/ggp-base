package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.player.gamer.statemachine.sancho.TreeNode;
import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlFunction;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonProposition;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;
import org.w3c.tidy.MutableInteger;

public class MajorityPropositionGoalsHeuristic implements Heuristic
{
  private static final Logger LOGGER = LogManager.getLogger();

  private ForwardDeadReckonPropnetStateMachine stateMachine = null;
  private MajorityPropositionGoalsCalculator   goalsCalculator = null;
  private RoleOrdering                         roleOrdering = null;
  private boolean                              reverseRoles = false;
  private boolean                              roleReversalFixed = false;
  private boolean                              predictionsMatch = true;
  private boolean                              tuningComplete = false;

  public MajorityPropositionGoalsHeuristic()
  {
  }

  @Override
  public boolean tuningInitialise(ForwardDeadReckonPropnetStateMachine xiStateMachine,
                                  RoleOrdering xiRoleOrdering)
  {
    stateMachine = xiStateMachine;
    roleOrdering = xiRoleOrdering;

    //  Only currently supported for 2 player games
    int numRoles = xiStateMachine.getRoles().length;
    if ( numRoles != 2 )
    {
      return false;
    }

    //  Is it plausible that the goals of this game are based on a majority count of some
    //  base prop sentence?
    for(PolymorphicProposition[] goals : xiStateMachine.getFullPropNet().getGoalPropositions().values())
    {
      for ( PolymorphicProposition p : goals)
      {
        int goalValue = Integer.parseInt(p.getName().getBody().get(1).toString());

        if ( goalValue != 0 && goalValue != 50 && goalValue != 100 )
        {
          return false;
        }
      }
    }

    GdlConstant basePropGoalFnName = null;
    List<Set<String>> paramRanges = new ArrayList<>();
    Map<Role, Set<PolymorphicProposition>> roalGoalsSupportingBaseProps = new HashMap<>();

    //  For each goal find the set of baseprops it is immediately dependent on
    for(Role role : xiStateMachine.getRoles())
    {
      Set<PolymorphicProposition> derivedFromBaseProps = new HashSet<>();

      for(PolymorphicProposition p : xiStateMachine.getFullPropNet().getGoalPropositions().get(role))
      {
        Set<PolymorphicComponent> processedComponents = new HashSet<>();
        recursiveFindFeedingBaseProps(p, derivedFromBaseProps, processedComponents);
      }

      for(PolymorphicProposition p : derivedFromBaseProps)
      {
        GdlSentence propName = p.getName();

        if (propName.arity() == 1 &&
            propName.getBody().get(0) instanceof GdlFunction)
        {
          GdlFunction propFn = (GdlFunction)propName.getBody().get(0);
          GdlConstant propFnName = propFn.getName();

          if ( basePropGoalFnName == null )
          {
            assert(propFnName != null);
            basePropGoalFnName = propFnName;

            for (int i = 0; i < propFn.arity(); i++)
            {
              paramRanges.add(new HashSet<String>());
            }
          }
          else if ( basePropGoalFnName != propFnName )
          {
            return false;
          }

          for (int i = 0; i < propFn.arity(); i++)
          {
            paramRanges.get(i).add(propFn.getBody().get(i).toString());
          }
        }
      }

      roalGoalsSupportingBaseProps.put(role, derivedFromBaseProps);
    }

    if ( basePropGoalFnName == null )
    {
      return false;
    }

    //  Is one of the param ranges plausibly a role?
    int bestParamIndex = -1;

    for(int i = 0; i < paramRanges.size(); i++)
    {
      if ( paramRanges.get(i).size() == numRoles )
      {
        bestParamIndex = i;
        break;
      }
    }

    if ( bestParamIndex == -1 )
    {
      return false;
    }

    goalsCalculator = new MajorityPropositionGoalsCalculator();

    String previousRoleVal = null;

    for(Role role : xiStateMachine.getRoles())
    {
      String roleVal = null;
      ForwardDeadReckonInternalMachineState roleMask = new ForwardDeadReckonInternalMachineState(xiStateMachine.getInfoSet());

      for(PolymorphicProposition p : roalGoalsSupportingBaseProps.get(role))
      {
        GdlFunction propFn = (GdlFunction)p.getName().getBody().get(0);
        String roleParamValue = propFn.getBody().get(bestParamIndex).toString();

        if ( roleVal == null && !roleParamValue.equals(previousRoleVal))
        {
          roleVal = roleParamValue;
        }

        if ( roleParamValue.equals(roleVal) )
        {
          roleMask.add(((ForwardDeadReckonProposition)p).getInfo());
        }
      }

      goalsCalculator.addRoleMask(role, roleMask);
      previousRoleVal = roleVal;
    }

    return true;
  }

  @Override
  public void tuningStartSampleGame()
  {
    // TODO Auto-generated method stub

  }

  @Override
  public void tuningInterimStateSample(ForwardDeadReckonInternalMachineState xiState,
                                       int xiChoosingRoleIndex)
  {
    // TODO Auto-generated method stub

  }

  @Override
  public void tuningTerminalStateSample(ForwardDeadReckonInternalMachineState xiState,
                                        int[] xiRoleScores)
  {
    if ( predictionsMatch )
    {
      for (int i = 0; i < xiRoleScores.length; i++)
      {
        int predictedScore = goalsCalculator.getGoalValue(xiState, roleOrdering.roleIndexToRole(i));

        switch(predictedScore)
        {
          case 0:
            if ( xiRoleScores[i] == 0 )
            {
              if ( roleReversalFixed )
              {
                if ( reverseRoles )
                {
                  predictionsMatch = false;
                }
              }
              else
              {
                roleReversalFixed = true;
              }
            }
            else if ( xiRoleScores[i] == 100 )
            {
              if ( roleReversalFixed )
              {
                if ( !reverseRoles )
                {
                  predictionsMatch = false;
                }
              }
              else
              {
                roleReversalFixed = true;
                reverseRoles = true;
              }
            }
            else
            {
              predictionsMatch = false;
            }
            break;
          case 50:
            if ( xiRoleScores[i] != 50 )
            {
              predictionsMatch = false;
            }
            break;
          case 100:
            if ( xiRoleScores[i] == 100 )
            {
              if ( roleReversalFixed )
              {
                if ( reverseRoles )
                {
                  predictionsMatch = false;
                }
              }
              else
              {
                roleReversalFixed = true;
              }
            }
            else if ( xiRoleScores[i] == 0 )
            {
              if ( roleReversalFixed )
              {
                if ( !reverseRoles )
                {
                  predictionsMatch = false;
                }
              }
              else
              {
                roleReversalFixed = true;
                reverseRoles = true;
              }
            }
            else
            {
              predictionsMatch = false;
            }
            break;
          default:
            assert(false);
            break;
        }
      }
    }
  }

  @Override
  public void tuningComplete()
  {
    tuningComplete = true;

    if ( predictionsMatch )
    {
      if ( reverseRoles )
      {
        goalsCalculator.reverseRoles();
      }
      stateMachine.setGoalsCalculator(goalsCalculator);

      LOGGER.info("Using emulated goals calculator (majority)");
    }
  }

  @Override
  public void newTurn(ForwardDeadReckonInternalMachineState xiState,
                      TreeNode xiNode)
  {
  }

  @Override
  public void getHeuristicValue(ForwardDeadReckonInternalMachineState xiState,
                                ForwardDeadReckonInternalMachineState xiPreviousState,
                                double[] xiXoHeuristicValue,
                                MutableInteger xiXoHeuristicWeight)
  {
  }

  @Override
  public boolean isEnabled()
  {
    return !tuningComplete;
  }

  @Override
  public Heuristic createIndependentInstance()
  {
    return this;
  }

  private void recursiveFindFeedingBaseProps(PolymorphicComponent c, Set<PolymorphicProposition> baseProps, Set<PolymorphicComponent> processedComponents)
  {
    if ( processedComponents.contains(c))
    {
      return;
    }

    processedComponents.add(c);

    if ( c instanceof PolymorphicProposition )
    {
      if ( stateMachine.getFullPropNet().getBasePropositions().values().contains(c))
      {
        baseProps.add((PolymorphicProposition)c);
      }
    }

    if ( c instanceof PolymorphicTransition )
    {
      return;
    }

    for(PolymorphicComponent input : c.getInputs())
    {
      recursiveFindFeedingBaseProps(input, baseProps, processedComponents);
    }
  }
}
