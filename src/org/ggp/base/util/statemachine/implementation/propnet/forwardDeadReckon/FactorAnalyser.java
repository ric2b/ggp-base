package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.player.gamer.statemachine.sancho.RuntimeGameCharacteristics;
import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlPool;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonProposition;

/**
 * @author steve
 * class responsible for analysing a game's propnet to determine its factors
 */
public class FactorAnalyser
{
  private static final Logger LOGGER = LogManager.getLogger();

  private ForwardDeadReckonPropnetStateMachine stateMachine;
  static final private GdlConstant    GOAL      = GdlPool.getConstant("goal");
  static final private GdlConstant    INIT      = GdlPool.getConstant("init");
  static final private GdlConstant    TERMINAL  = GdlPool.getConstant("terminal");
  static final private GdlConstant    LEGAL  = GdlPool.getConstant("legal");
  static final private GdlConstant    DOES  = GdlPool.getConstant("does");
  static final private GdlConstant    BASE  = GdlPool.getConstant("base");

  private DependencyCache componentDirectBaseDependencies;
  private Map<PolymorphicComponent, DependencyInfo> basePropositionDependencies = new HashMap<>();
  private int numBaseProps;
  private final Collection<PolymorphicProposition> basePropositions;
  private final Set<PolymorphicProposition> controlSet = new HashSet<>();
  private boolean mbControlSetCalculated = false;

  private class DirectDependencyInfo
  {
    public Set<PolymorphicProposition>  dependencies = new HashSet<>();
    public Set<ForwardDeadReckonLegalMoveInfo> moves = new HashSet<>();

    public DirectDependencyInfo()
    {
    }

    public void add(DirectDependencyInfo other)
    {
      dependencies.addAll(other.dependencies);
      moves.addAll(other.moves);
    }

    public void buildImmediateBaseDependencies(PolymorphicComponent c)
    {
      FactorAnalyser.this.buildImmediateBaseDependencies(c, this);
    }
  }

  //  An LRU cache is used to cache component dependencies.  The size of this
  //  cache is a trade-off between performance of walking the dependency graph
  //  and memory consumption (which can equate to performance via GC time)
  private class DependencyInfo extends DirectDependencyInfo
  {
    public DependencyInfo()
    {
    }

    @Override
    public void buildImmediateBaseDependencies(PolymorphicComponent c)
    {
      FactorAnalyser.this.buildImmediateBaseDependencies(c, this);

      //  The 0th level dependencies are the immediate dependencies
      dependenciesByLevel.add(new HashSet<>(dependencies));
      movesByLevel.add(new HashSet<>(moves));
    }

    public List<Set<PolymorphicProposition>>  dependenciesByLevel = new ArrayList<>();
    public List<Set<ForwardDeadReckonLegalMoveInfo>>  movesByLevel = new ArrayList<>();
  }

  private class DependencyCache
  extends
  LinkedHashMap<PolymorphicComponent, DirectDependencyInfo>
  {
    /**
     *
     */
    private static final long serialVersionUID = 1L;
    private int               maxEntries;

    public DependencyCache(int capacity)
    {
      super(capacity + 1, 1.0f, true);
      maxEntries = capacity;
    }

    @Override
    protected boolean removeEldestEntry(final Map.Entry<PolymorphicComponent, DirectDependencyInfo> eldest)
    {
      return super.size() > maxEntries;
    }
  }

  public FactorAnalyser(ForwardDeadReckonPropnetStateMachine stateMachine)
  {
    this.stateMachine = stateMachine;
    basePropositions = stateMachine.getFullPropNet().getBasePropositions().values();
    numBaseProps = basePropositions.size();
    componentDirectBaseDependencies = new DependencyCache(5000);
  }

  private DirectDependencyInfo getComponentDirectDependencies(PolymorphicComponent c)
  {
    if ( basePropositions.contains(c) )
    {
      if ( !basePropositionDependencies.containsKey(c))
      {
        DependencyInfo newInfo = new DependencyInfo();

        //  Do the put into the map BEFORE trying to recurse as otherwise loops
        //  will be infinite!
        basePropositionDependencies.put(c, newInfo);
        newInfo.buildImmediateBaseDependencies(c);

        return newInfo;
      }

      return basePropositionDependencies.get(c);
    }
    else if ( !componentDirectBaseDependencies.containsKey(c) )
    {
      DirectDependencyInfo newInfo = new DirectDependencyInfo();

      //  Do the put into the map BEFORE trying to recurse as otherwise loops
      //  will be infinite!  We also cache components with many inputs, as they will
      //  be expensive to recalculate
      if ( c instanceof PolymorphicProposition || c.getInputs().size() > 5 )
      {
        componentDirectBaseDependencies.put(c, newInfo);
      }
      newInfo.buildImmediateBaseDependencies(c);

      return newInfo;
    }

    return componentDirectBaseDependencies.get(c);
  }

  /**
   * @param xiTimeout             - When to give up if the analysis takes too long.
   * @param xiGameCharacteristics - Information learned about this game.
   *
   * @return  The identified factors (or null if there are none or we ran out of time).
   */
  public Set<Factor> analyse(long xiTimeout, RuntimeGameCharacteristics xiGameCharacteristics)
  {
    Set<Factor> factors = new HashSet<>();
    long lAbortTime = System.currentTimeMillis() + xiTimeout;

    //  Construct the full closure of base dependencies
    for(PolymorphicProposition baseProp : basePropositions)
    {
      DependencyInfo dInfo = (DependencyInfo)getComponentDirectDependencies(baseProp);
      int depth = 1;

      Set<PolymorphicProposition> dependenciesAtDepth;
      Set<ForwardDeadReckonLegalMoveInfo> movesAtDepth;
      do
      {
        dependenciesAtDepth = new HashSet<>();
        dInfo.dependenciesByLevel.add(dependenciesAtDepth);
        movesAtDepth = new HashSet<>();
        dInfo.movesByLevel.add(movesAtDepth);

        for(PolymorphicProposition fringeDependency : dInfo.dependenciesByLevel.get(depth-1))
        {
          dependenciesAtDepth.addAll(getComponentDirectDependencies(fringeDependency).dependencies);
          movesAtDepth.addAll(getComponentDirectDependencies(fringeDependency).moves);

          if ( dependenciesAtDepth.size() >= numBaseProps )
          {
            break;
          }

          //  If the analysis is just taking too long give up
          if (System.currentTimeMillis() > lAbortTime)
          {
            LOGGER.warn("Factorization analysis timed out after at least " + xiTimeout + "ms");
            xiGameCharacteristics.factoringFailedAfter(xiTimeout);
            return null;
          }
        }

        dependenciesAtDepth.removeAll(dInfo.dependencies);
        movesAtDepth.removeAll(dInfo.moves);

        dInfo.dependencies.addAll(dependenciesAtDepth);
        dInfo.moves.addAll(movesAtDepth);

        depth++;
      } while(!dependenciesAtDepth.isEmpty() && dInfo.dependencies.size() < numBaseProps);

      // If we find a base prop that depends on more than 2/3rds of the others assume we're not going to be able to
      // factorize, so stop wasting time on the factorization analysis.
      if (dInfo.dependencies.size() > (numBaseProps * 2) / 3)
      {
        return null;
      }
    }

    //  Now look for pure disjunctive inputs to goal and terminal
    Map<PolymorphicComponent, DependencyInfo> disjunctiveInputs = new HashMap<>();

    addDisjunctiveInputProps(stateMachine.getFullPropNet().getTerminalProposition(), disjunctiveInputs);
    //  TODO - same for goals

    //  Trim out from each disjunctive input set those propositions in the control set, which are only
    //  influenced by other base props independently of moves (usually step and control logic)
    Set<PolymorphicComponent> controlOnlyInputs = new HashSet<>();

    for(PolymorphicProposition baseProp : basePropositions)
    {
      DependencyInfo dInfo = (DependencyInfo)getComponentDirectDependencies(baseProp);

      if ( dInfo.moves.isEmpty() )
      {
        controlSet.add(baseProp);
      }
    }

    mbControlSetCalculated = true;

    for( Entry<PolymorphicComponent, DependencyInfo> e : disjunctiveInputs.entrySet())
    {
      e.getValue().dependencies.removeAll(controlSet);
      if ( e.getValue().dependencies.isEmpty())
      {
        controlOnlyInputs.add(e.getKey());
      }
    }

    //  Trim out those inputs which have no base dependencies
    for(PolymorphicComponent c : controlOnlyInputs)
    {
      disjunctiveInputs.remove(c);
    }

    //  TOTAL HACK - TEMPORARY (I hope)
    //  The way the GDL is written for several simultaneous games introduces an
    //  artificial pseudo-coupling for the termination condition that amounts to
    //  someone-has-no-moves-left 'stalemates'.  Examples are:
    //    All board positions occupied in C4
    //    One player has no pieces left in Breakthrough
    //  The GDL tends to express these as conditions across both games in factorized
    //  situations.  For instance simultaneous C4 expresses this as 'there are no blank
    //  cells left on either board' which amounts to a NOT of a large OR for every cell
    //  being red of black.
    //  In general these psuedo-couplings are quite hard to pick out in static analysis
    //  and there are a number of possible (somewhat) robust approaches we could try:
    //    1)  Disconnect them from the terminal clause and directly monitor them during
    //        simulation, but replace them in the terminal disjunction with the extra
    //        pseudo-condition 'the legal move set is empty'.  During metagaming simulate
    //        this modified game, and if the suspect disjunct we are monitoring directly
    //        only goes TRUE in states that the modified game finds to ANYWAY be terminal
    //        then they are not true couplings of the factors.
    //    2)  Rewrite them as a disjunctive approximation by making them ORs of two
    //        instances of their input logic obtained by pruning out all base props
    //        from one of the factors.  Simulate both versions of the game, and if
    //        they never disagree about termination then the coupling is not a true
    //        coupling and we can use the factored approximation
    //  HOWEVER, for now we take a much cheaper seem-to-get-away-with-it approach
    //  and consider the size of the dependency sets for the disjunctive propositions - if
    //  any of them involve more than half of the (non-control) base-props we assume they
    //  can be ignored for the purposes of determining factorization!
    Set<PolymorphicComponent> ignorableDisjuncts = new HashSet<>();

    for( Entry<PolymorphicComponent, DependencyInfo> e : disjunctiveInputs.entrySet())
    {
      if ( e.getValue().dependencies.size() > (numBaseProps - controlSet.size())/2 )
      {
        ignorableDisjuncts.add(e.getKey());
      }
    }

    for(PolymorphicComponent c : ignorableDisjuncts)
    {
      disjunctiveInputs.remove(c);
    }

    //  Now find sets of disjunctive inputs with non-intersecting base prop
    //  dependencies - these are the factors
    while(!disjunctiveInputs.isEmpty())
    {
      //  If the analysis is just taking too long give up
      if (System.currentTimeMillis() > lAbortTime)
      {
        LOGGER.warn("Factorization analysis (post dependency phase) timed out after at least " + xiTimeout + "ms");
        xiGameCharacteristics.factoringFailedAfter(xiTimeout);
        return null;
      }

      Factor newFactor = new Factor(stateMachine);

      newFactor.addAll(disjunctiveInputs.values().iterator().next().dependencies);
      factors.add(newFactor);

      for(Factor factor : factors)
      {
        boolean anyAdded;

        do
        {
          anyAdded = false;

          Set<PolymorphicComponent> inputsProcessed = new HashSet<>();

          for( Entry<PolymorphicComponent, DependencyInfo> e : disjunctiveInputs.entrySet())
          {
            if ( factor.containsAny(e.getValue().dependencies) )
            {
              factor.addAll(e.getValue().dependencies);
              factor.addAllMoves(e.getValue().moves);
              inputsProcessed.add(e.getKey());
              anyAdded = true;
            }
          }

          for(PolymorphicComponent p : inputsProcessed)
          {
            disjunctiveInputs.remove(p);
          }
        } while(anyAdded);
      }
    }

    //  If we only found 1 factor (or none, which is possible with the current hacky
    //  approach to handling global couplings) then we do not have a factorizable
    //  game, and we can leave the cross ref info recording null for the factor of every
    //  base prop, but if we have factorized then record the factor each base prop belongs to
    //  (if any - pure control props are not included in any factor)
    if ( factors.size() > 1 )
    {
      for(Factor factor : factors)
      {
        factor.dump();

        for(PolymorphicComponent c : factor.getComponents())
        {
          if (c instanceof PolymorphicProposition)
          {
            PolymorphicProposition p = (PolymorphicProposition)c;

            ForwardDeadReckonProposition fdrp = (ForwardDeadReckonProposition)p;
            ForwardDeadReckonPropositionCrossReferenceInfo info = (ForwardDeadReckonPropositionCrossReferenceInfo)fdrp.getInfo();

            info.factor = factor;
          }
        }
      }
    }

    xiGameCharacteristics.setFactors(factors);
    return (factors.size() > 1 ? factors : null);
  }

  private void addDisjunctiveInputProps(PolymorphicComponent c, Map<PolymorphicComponent, DependencyInfo> disjunctiveInputs)
  {
    recursiveAddDisjunctiveInputProps(c.getSingleInput(), disjunctiveInputs);
  }

  private void recursiveAddDisjunctiveInputProps(PolymorphicComponent c, Map<PolymorphicComponent, DependencyInfo> disjunctiveInputs)
  {
    if ( c instanceof PolymorphicOr )
    {
      for(PolymorphicComponent input : c.getInputs())
      {
        recursiveAddDisjunctiveInputProps(input, disjunctiveInputs);
      }
    }
    else
    {
      //  For each disjunctive input find the base props it is dependent on
      DirectDependencyInfo immediateDependencies = getComponentDirectDependencies(c);

      DependencyInfo fullDependencies = new DependencyInfo();

      //  Construct the full dependencies from the already calculated base dependencies map
      for(PolymorphicProposition p : immediateDependencies.dependencies)
      {
        DirectDependencyInfo immediateDependentDependencies = getComponentDirectDependencies(p);

        fullDependencies.add(immediateDependentDependencies);
        if ( fullDependencies.dependencies.size() >= numBaseProps )
        {
          break;
        }
      }
      disjunctiveInputs.put(c, fullDependencies);
    }
  }

  DirectDependencyInfo buildImmediateBaseDependencies(PolymorphicComponent xiC, DirectDependencyInfo result)
  {
    recursiveBuildImmediateBaseDependencies(xiC, xiC, result);

    return result;
  }

  //  Return true if at least one dependency involved transitioning across a does->legal relationship
  private void recursiveBuildImmediateBaseDependencies(PolymorphicComponent root, PolymorphicComponent c, DirectDependencyInfo dInfo)
  {
    if ( c instanceof PolymorphicProposition )
    {
      if ( dInfo.dependencies.contains(c))
      {
        return;
      }

      PolymorphicProposition p = (PolymorphicProposition)c;
      GdlConstant name = p.getName().getName();

      if ( basePropositions.contains(p))
      {
        dInfo.dependencies.add(p);
        if ( root != p )
        {
          return;
        }
      }

      if (name.equals(INIT) )
      {
        return;
      }

      if ( name.equals(DOES))
      {
        if ( root != null )
        {
          PolymorphicProposition legalProp = stateMachine.getFullPropNet().getLegalInputMap().get(c);

          if ( legalProp != null )
          {
            DirectDependencyInfo legalPropInfo = getComponentDirectDependencies(legalProp);

            ForwardDeadReckonLegalMoveInfo[] masterMoveList = stateMachine.getFullPropNet().getMasterMoveList();
            ForwardDeadReckonLegalMoveInfo moveInfo = masterMoveList[((ForwardDeadReckonProposition)legalProp).getInfo().index];

            dInfo.add(legalPropInfo);

            dInfo.moves.add(moveInfo);
          }
        }

        return;
      }
    }

    //  The following is a somewhat heuristic approach to getting around 'fake' dependencies
    //  introduced by distinct clauses in the GDL.  Specifically games often have rules of this
    //  type (taken from KnighThrough):
    //    (<= (next (cell ?x ?y ?state))
    //        (true (cell ?x ?y ?state))
    //        (does ?player (move ?x1 ?y1 ?x2 ?y2))
    //        (distinctcell ?x ?y ?x1 ?y1)
    //        (distinctcell ?x ?y ?x2 ?y2))
    //  This is saying that a cell retains its state unless someone moves into or out of it
    //  The problem is that it encodes this logic by testing that what is played is one of the
    //  set of moves that is not a move into or out of the cell in question - i.e. - a vast OR
    //  consisting of most moves in the game, and in particular, including all the moves on the
    //  'other board' relative to the tested cell.  Naively this coupling of a move on the other
    //  board to the next state of a cell on this board looks like a dependency that prevents
    //  factorization, but we must prevent this being seen as a 'real' coupling.  This is a result
    //  of the (not encoded into the propnet) requirement (for a well formed game) that exactly
    //  one move must be played each turn, so this huge OR is equivalent to the NOT of a much smaller
    //  OR for the complimentary set of moves.  We convert ORs involving more than half of the moves in
    //  the game with that transformation and calculate the dependencies with respect to the result.
    if ( c instanceof PolymorphicOr )
    {
      Collection<PolymorphicProposition> inputProps = stateMachine.getFullPropNet().getInputPropositions().values();
      if ( c.getInputs().size() > inputProps.size()/2 )
      {
        Set<PolymorphicComponent> inputPropsOred = new HashSet<>();

        for(PolymorphicComponent input : c.getInputs())
        {
          if ( inputProps.contains(input))
          {
            inputPropsOred.add(input);
          }
        }

        if ( inputPropsOred.size() > inputProps.size()/2 )
        {
          for(PolymorphicComponent input : c.getInputs())
          {
            if ( !inputPropsOred.contains(input))
            {
              DirectDependencyInfo inputPropInfo = getComponentDirectDependencies(input);

              dInfo.add(inputPropInfo);
            }
          }
          for(PolymorphicComponent input : inputProps)
          {
            if ( !inputPropsOred.contains(input))
            {
              DirectDependencyInfo inputPropInfo = getComponentDirectDependencies(input);

              dInfo.add(inputPropInfo);
            }
          }

          return;
        }
      }
    }

    for(PolymorphicComponent input : c.getInputs())
    {
      if ( basePropositions.contains(input))
      {
        dInfo.dependencies.add((PolymorphicProposition)input);
      }
      else
      {
        DirectDependencyInfo inputPropInfo = getComponentDirectDependencies(input);

        dInfo.add(inputPropInfo);
      }
    }
  }

  /**
   * Get the set of base propositions that constitute the control set,
   * which is the set of base props whose values do not depend on the moves played
   * at any stage of the game (e.g. - step counters etc.)
   * @return set of props in the control set of null if not available
   */
  public Set<PolymorphicProposition> getControlProps()
  {
    if ( mbControlSetCalculated )
    {
      return controlSet;
    }

    return null;
  }
}
