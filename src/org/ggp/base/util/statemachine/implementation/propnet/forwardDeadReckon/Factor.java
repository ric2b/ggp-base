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
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonProposition;
import org.ggp.base.util.statemachine.Move;

/**
 * Class representing a factor within a game's propnet.  A factor is a partition within a partitioning of the base
 * propositions into disjoint sets between which there are no causative logical connections or coupling via
 * terminal/goal conditions.
 */
public class Factor
{
  private static final Logger LOGGER = LogManager.getLogger();

  private static final ForwardDeadReckonLegalMoveInfo PSEUDO_NO_OP = new ForwardDeadReckonLegalMoveInfo(true);

  private Set<PolymorphicComponent> components = new HashSet<>();
  private Set<Move> moves = new HashSet<>();
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

  public Set<Move> getMoves()
  {
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

  public void addAllMoves(Collection<Move> toAdd)
  {
    moves.addAll(toAdd);
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
    for (Move move : moves)
    {
      LOGGER.debug("  " + move);
    }
  }

  private void setUpStateMasks()
  {
    factorSpecificStateMask = new ForwardDeadReckonInternalMachineState(stateMachine.getInfoSet());
    for(PolymorphicProposition p : stateMachine.getFullPropNet().getBasePropositions().values())
    {
      ForwardDeadReckonProposition fdrp = (ForwardDeadReckonProposition)p;
      ForwardDeadReckonPropositionCrossReferenceInfo info = (ForwardDeadReckonPropositionCrossReferenceInfo)fdrp.getInfo();

      if ( info.factor == this )
      {
        factorSpecificStateMask.add(info);
      }
    }
    stateMask = new ForwardDeadReckonInternalMachineState(stateMachine.getInfoSet());
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

  /**
   * Get the next move for this factor - inserts pseudo-noops if required
   * @param xiFactor - facor to enumerate moves for
   * @param itr - iterator on the collection of all moves
   * @return next factoo move
   */
  public static ForwardDeadReckonLegalMoveInfo nextFactorMove(Factor xiFactor,
                                                          Iterator<ForwardDeadReckonLegalMoveInfo> itr)
  {
    ForwardDeadReckonLegalMoveInfo result;

    while(itr.hasNext())
    {
      result = itr.next();
      if ( result.factor == xiFactor || result.factor == null || xiFactor == null )
      {
        return result;
      }
    }

    assert(xiFactor != null);

    // The extra move must be a forced noop
    return PSEUDO_NO_OP;
  }

  /**
   * @return the number of moves in the specified collection that are valid for the specified factor.
   *
   * @param xiMoves  - the moves.
   * @param xiFactor - the factor.
   * @param includeForcedPseudoNoops - whether to include forced pseudo-noops in factors
   */
  public static int getFilteredSize(Collection<ForwardDeadReckonLegalMoveInfo> xiMoves, Factor xiFactor, boolean includeForcedPseudoNoops)
  {
    if (xiFactor == null)
    {
      // Non-factored game.  All moves in the underlying collection are valid.
      return xiMoves.size();
    }

    int lCount = 0;
    boolean noopFound = false;
    for (ForwardDeadReckonLegalMoveInfo lMove : xiMoves)
    {
      if (lMove.factor == null || lMove.factor == xiFactor)
      {
        lCount++;

        if ( lMove.inputProposition == null || lMove.factor == null )
        {
          noopFound = true;
        }
      }
    }

    if ( lCount == 0 || (includeForcedPseudoNoops && !noopFound && xiFactor.getAlwaysIncludePseudoNoop()))
    {
      lCount++;
    }

    return lCount;
  }
}
