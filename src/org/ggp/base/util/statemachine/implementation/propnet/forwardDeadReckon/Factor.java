package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonProposition;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;

/**
 * Class representing a factor within a game's propnet.  A factor is a partition within a partitioning of the base
 * propositions into disjoint sets between which there are no causative logical connections or coupling via
 * terminal/goal conditions.
 */
public class Factor implements StateMachineFilter
{
  private static final Logger LOGGER = LogManager.getLogger();

  private static final ForwardDeadReckonLegalMoveInfo PSEUDO_NO_OP = new ForwardDeadReckonLegalMoveInfo(true);

  private Set<PolymorphicComponent> components = new HashSet<>();
  private Set<ForwardDeadReckonLegalMoveInfo>   moveInfos = new HashSet<>();
  private Set<Move>                             moves = null;
  private ForwardDeadReckonInternalMachineState stateMask = null;
  private ForwardDeadReckonInternalMachineState factorSpecificStateMask = null;
  private ForwardDeadReckonInternalMachineState inverseStateMask = null;
  private ForwardDeadReckonInternalMachineState inverseFactorSpecificStateMask = null;
  private ForwardDeadReckonPropnetStateMachine stateMachine;
  private boolean alwaysIncludePseudoNoop = false;

  public Factor(ForwardDeadReckonPropnetStateMachine stateMachine)
  {
    this.stateMachine = stateMachine;
  }

  public boolean containsComponent(PolymorphicComponent c)
  {
    return components.contains(c);
  }

  public void addComponent(PolymorphicComponent c)
  {
    components.add(c);
  }

  public Set<PolymorphicComponent> getComponents()
  {
    return components;
  }

  public Set<ForwardDeadReckonLegalMoveInfo> getMoveInfos()
  {
    return moveInfos;
  }

  public Set<Move> getMoves()
  {
    if ( moves == null )
    {
      moves = new HashSet<>();

      for(ForwardDeadReckonLegalMoveInfo moveInfo : moveInfos)
      {
        moves.add(moveInfo.move);
      }
    }
    return moves;
  }

  public boolean containsAny(Collection<? extends PolymorphicComponent> toTest)
  {
    for(PolymorphicComponent c : toTest)
    {
      if ( components.contains(c))
      {
        return true;
      }
    }

    return false;
  }

  public void addAll(Collection<? extends PolymorphicComponent> toAdd)
  {
    components.addAll(toAdd);
  }

  public void addAllMoves(Collection<ForwardDeadReckonLegalMoveInfo> toAdd)
  {
    moveInfos.addAll(toAdd);
  }

  public void dump()
  {
    LOGGER.debug("Factor base props:");
    for (PolymorphicComponent c : components)
    {
      if (c instanceof PolymorphicProposition)
      {
        PolymorphicProposition p = (PolymorphicProposition)c;

        LOGGER.debug("  " + p.getName());
      }
    }

    LOGGER.debug("Factor moves:");
    for (ForwardDeadReckonLegalMoveInfo moveInfo : moveInfos)
    {
      LOGGER.debug("  " + moveInfo.move);
    }
  }

  private void setUpStateMasks()
  {
    factorSpecificStateMask = stateMachine.createEmptyInternalState();
    for(PolymorphicProposition p : stateMachine.getFullPropNet().getBasePropositions().values())
    {
      ForwardDeadReckonProposition fdrp = (ForwardDeadReckonProposition)p;
      ForwardDeadReckonPropositionCrossReferenceInfo info = (ForwardDeadReckonPropositionCrossReferenceInfo)fdrp.getInfo();

      if ( info.factor == this )
      {
        factorSpecificStateMask.add(info);
      }
    }
    stateMask = stateMachine.createEmptyInternalState();
    for(PolymorphicProposition p : stateMachine.getFullPropNet().getBasePropositions().values())
    {
      ForwardDeadReckonProposition fdrp = (ForwardDeadReckonProposition)p;
      ForwardDeadReckonPropositionCrossReferenceInfo info = (ForwardDeadReckonPropositionCrossReferenceInfo)fdrp.getInfo();

      if ( info.factor == null )
      {
        stateMask.add(info);
      }
    }
    stateMask.merge(factorSpecificStateMask);

    inverseStateMask = new ForwardDeadReckonInternalMachineState(stateMask);
    inverseStateMask.invert();
    inverseFactorSpecificStateMask = new ForwardDeadReckonInternalMachineState(factorSpecificStateMask);
    inverseFactorSpecificStateMask.invert();
  }

  public ForwardDeadReckonInternalMachineState getStateMask(boolean stateSpecificOnly)
  {
    if ( factorSpecificStateMask == null )
    {
      setUpStateMasks();
    }

    return (stateSpecificOnly ? factorSpecificStateMask : stateMask);
  }

  public ForwardDeadReckonInternalMachineState getInverseStateMask(boolean stateSpecificOnly)
  {
    if ( factorSpecificStateMask == null )
    {
      setUpStateMasks();
    }

    return (stateSpecificOnly ? inverseFactorSpecificStateMask : inverseStateMask);
  }

  public boolean getAlwaysIncludePseudoNoop()
  {
    return alwaysIncludePseudoNoop;
  }

  public void setAlwaysIncludePseudoNoop(boolean value)
  {
    alwaysIncludePseudoNoop = value;
  }

  @Override
  public boolean isFilteredTerminal(ForwardDeadReckonInternalMachineState xiState)
  {
    return stateMachine.isTerminal(xiState);
  }

  private int getFilteredMovesSize(Collection<ForwardDeadReckonLegalMoveInfo> xiMoves, boolean xiIncludeForcedPseudoNoops)
  {
    int lCount = 0;
    boolean noopFound = false;
    for (ForwardDeadReckonLegalMoveInfo lMove : xiMoves)
    {
      if (lMove.factor == null || lMove.factor == this)
      {
        lCount++;

        if ( lMove.inputProposition == null || lMove.factor == null )
        {
          noopFound = true;
        }
      }
    }

    if ( lCount == 0 || (xiIncludeForcedPseudoNoops && !noopFound && getAlwaysIncludePseudoNoop()))
    {
      lCount++;
    }

    return lCount;
  }

  @Override
  public int getFilteredMovesSize(ForwardDeadReckonInternalMachineState xiState,
                                  ForwardDeadReckonLegalMoveSet xiMoves,
                                  Role role,
                                  boolean xiIncludeForcedPseudoNoops)
  {
    return getFilteredMovesSize(xiMoves.getContents(role), xiIncludeForcedPseudoNoops);
  }

  @Override
  public int getFilteredMovesSize(ForwardDeadReckonInternalMachineState xiState,
                                  ForwardDeadReckonLegalMoveSet xiMoves,
                                  int roleIndex,
                                  boolean xiIncludeForcedPseudoNoops)
  {
    return getFilteredMovesSize(xiMoves.getContents(roleIndex), xiIncludeForcedPseudoNoops);
  }

  @Override
  public ForwardDeadReckonLegalMoveInfo nextFilteredMove(Iterator<ForwardDeadReckonLegalMoveInfo> xiItr)
  {
    ForwardDeadReckonLegalMoveInfo result;

    while(xiItr.hasNext())
    {
      result = xiItr.next();
      if ( result.factor == this || result.factor == null )
      {
        return result;
      }
    }

    // The extra move must be a forced noop
    return PSEUDO_NO_OP;
  }
}
