package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;
import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;
import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;

/**
 * @author steve
 * Fast animator for the propNet.  This class builds a highly optimized tabular representation
 * of the propNet and animates it.  This provides a high degree of memory locality of reference
 * as well as minimal code overheads at simulation-time
 */
public class ForwardDeadReckonPropnetFastAnimator
{
  /**
   * @author steve
   * Opaque class that is not interpreted externally but must be retrieved and passed back
   * to the fast animator in some of its APIs.  It holds state for an instance
   */
  public class InstanceInfo
  {
    /**
     * Construct a new instance state structure
     *
     * @param numComponents Number of components comprising the fast animator
     * representation of the network
     */
    InstanceInfo(int numComponents)
    {
      state = new int[numComponents];
      checkpointState = new int[numComponents];
    }

    public void saveCheckpoint()
    {
      System.arraycopy(state, 0, checkpointState, 0, state.length);
      legalMoveNotifier.saveCheckpoint();
      propositionTransitionNotifier.saveCheckpoint();
    }

    public void revertToCheckpoint()
    {
      System.arraycopy(checkpointState, 0, state, 0, state.length);
      legalMoveNotifier.revertToCheckpoint();
      propositionTransitionNotifier.revertToCheckpoint();
    }

    /**
     * Vector of state values for each component indexed by component id
     */
    final int[]                                   state;
    final int[]                                   checkpointState;
    /**
     * Interface to call for changes to the output state of legal move props
     */
    ForwardDeadReckonComponentTransitionNotifier  legalMoveNotifier;
    /**
     * Interface to call for changes to the output state of transitions
     */
    ForwardDeadReckonComponentTransitionNotifier  propositionTransitionNotifier;
    /**
     * Holds the current component id up to which an in-progress reset() has processed
     */
    int                                           resetWatermark;
  }

  private final ForwardDeadReckonPropNet  propNet;
  private int                             numTabularComponents = 0;
  private int                             nextComponentBaseId = 0;
  //  The data for each component is stored in a contiguous set of integers in
  //  the following form:
  //  METADATA (component type, output inversion, input count)
  //  NUM OUTPUTS
  //  ids of output components (NUM OUTPUTS entries)
  //  Array containing the ids of outputs to each component, packed consecutively.  The offset for
  //  the first entry for each component is stored in its componentInfo entry
  private final int[]              componentDataTable;

  private final int                componentMetaDataTypeMask = 0x07000000; //  3-bits for component type
  private final int                componentMetaDataTypeShift = 24;
  private final int                componentMetaDataOutputInverted = 0x08000000; //  Flag to indicate output is inverted (only applies to AND/OR)
  private final int                componentMetaDataInputCountMask = 0xFFFF; // Num inputs mask (16 bits)

  //  Array indexed by component id containing the index to call either the legal move
  //  or proposition transition trigger handler with.  In either case the value stored is the
  //  desired index shifted left 1 bit, and with 1 ORd in to flag the legal move case
  private final int[]              componentAssociatedTriggerIndexes;
  //private final PolymorphicComponent[] debugComponentMap;

  //  Component types
  private final int                componentTypeNonTriggeringProposition = 7;
  private final int                componentTypeProposition = 1;
  private final int                componentTypeTransition = 0;
  private final int                componentTypeOr = 2;
  //  Internally in the runtime representation AND/OR/NAND/NOR and NOT are all combined
  //  in a single universal logic component.  Because it doesn't exist in the same representations
  //  as the basic logic components it can use an id that overlaps with them, so we can keep types down to 8
  private final int                componentTypeUniversalLogic = 2;
  private final int                componentTypeAnd = 3;
  private final int                componentTypeNot = 4;
  private final int                componentTypeTrueConstant = 5;
  private final int                componentTypeFalseConstant = 6;
  private final int                componentTypePropositionBits = (componentTypeProposition << componentMetaDataTypeShift);
  private final int                componentTypeTransitionBits = (componentTypeTransition << componentMetaDataTypeShift);
  private final int                componentTypeOrBits = (componentTypeOr << componentMetaDataTypeShift);
  private final int                componentTypeAndBits = (componentTypeAnd << componentMetaDataTypeShift);
  private final int                componentTypeNotBits = (componentTypeNot << componentMetaDataTypeShift);
  private final int                componentTypeTrueConstantBits = (componentTypeTrueConstant << componentMetaDataTypeShift);
  private final int                componentTypeFalseConstantBits = (componentTypeFalseConstant << componentMetaDataTypeShift);
  private final int                componentTypeNonTriggeringPropositionBits = (componentTypeNonTriggeringProposition << componentMetaDataTypeShift);

  //  We us the top byte of the component id to encode some commonly required
  //  features so as to avoid the need for a lookup
  private final int                componentIdTypeMask = 0x07000000;
  private final int                componentIdTypeShift = 24;
  private final int                componentIdPropositionBits = (componentTypeProposition << componentIdTypeShift);
  private final int                componentIdTransitionBits = (componentTypeTransition << componentIdTypeShift);
  private final int                componentIdUniversalLogicBits = (componentTypeUniversalLogic << componentIdTypeShift);
  private final int                componentIdOutputUniversalLogicBits = (1 << 28);
  private final int                componentIdOutputInvertedFlag = (1<<29);

  // states will be represented by per-thread int arrays, with each int packed as follows
  private final int                componentStateCachedValMask = 1<<31; // Current state
  // Remaining bits are free for opaque use by the implementation.  For triggers (props with associated
  // legals or transitions) they are unused.  For the universal logic element it is a count which is
  // incremented on an input transitioning to TRUE, and decremented for a transition to FALSE.  These
  // increments/decrements are deliberately allowed to overflow into the sign bit, which is the state mask
  // This allows a single inc/dec to operate the components as any of OR/AND/NOR/NAND depending on how
  // reset initializes the value

  //  Reserved id used to denote a component whose id has not yet been set
  private final int notSetComponentId = -2;
  //  Reserved id used to denote a component which has been subsumed into another
  //  component for fast propagation
  private final int notNeededComponentId = -1;

  // Each instance (which typically maps to a thread) has its own InstanceInfo to hold the complete state
  // of that instance's propNet
  private InstanceInfo[]           instances;

  /**
   * Constructs a new fast animator for the given network.  The network must not be changed
   * after this call is made or the generated fast animator will no longer be valid
   *
   * @param thePropNet Network to construct an animator for
   */
  public ForwardDeadReckonPropnetFastAnimator(ForwardDeadReckonPropNet thePropNet)
  {
    propNet = thePropNet;

    int componentType = 0;

    //  Initialize component values to a distinguished 'not set' value
    for(PolymorphicComponent c : thePropNet.getComponents())
    {
      ((ForwardDeadReckonComponent)c).id = notSetComponentId;
    }

    //  Dummy components are (virtually) added to ensure that all outputs from any
    //  particular component are either all universal logic elements or are triggers.
    //  In either case this allows the propagation methods to assume that all outputs are
    //  of  matching type, and loop through them without type-checks.
    //  Where the original (PolymorphicComponent) network has a component with outputs of
    //  mixed type, we add a dummy 1-input OR buffer to separate the triggers from the
    //  logic
    Map<Integer, Set<PolymorphicComponent>> addedPassThroughComponents = new HashMap<>();

    //  Number components and set up metadata flags stored in their high id bits
    //  In order to make some attempt to improve CPU predictability of stride
    //  in the primary loop (which iterates over outputs of a component) we number
    //  so as to encourage generation of consecutive ids for outputs.
    //  Starting from the base propositions deal with their descendant networks
    //  first recursively, numbering components as they are encountered if not
    //  already numbered
    for(PolymorphicComponent c : propNet.getBasePropositions().values())
    {
      ForwardDeadReckonComponent fdrc = (ForwardDeadReckonComponent)c;

      if ( fdrc.id == notSetComponentId )
      {
        setComponentId(c, addedPassThroughComponents);
        recursivelySetComponentOutputIds(c, addedPassThroughComponents);
      }
    }

    //  Similarly the does props
    for(PolymorphicComponent c : propNet.getInputPropositions().values())
    {
      ForwardDeadReckonComponent fdrc = (ForwardDeadReckonComponent)c;

      if ( fdrc.id == notSetComponentId )
      {
        setComponentId(c, addedPassThroughComponents);
        recursivelySetComponentOutputIds(c, addedPassThroughComponents);
      }
    }

    //  Any remaining components
    for(PolymorphicComponent c : thePropNet.getComponents())
    {
      ForwardDeadReckonComponent fdrc = (ForwardDeadReckonComponent)c;

      if ( fdrc.id == notSetComponentId )
      {
        setComponentId(c, addedPassThroughComponents);
      }
    }

    componentAssociatedTriggerIndexes = new int[nextComponentBaseId/4];
    componentDataTable = new int[nextComponentBaseId];
    //debugComponentMap = new PolymorphicComponent[nextComponentBaseId/4];

    for(PolymorphicComponent c : thePropNet.getComponents())
    {
      ForwardDeadReckonComponent fdrc = (ForwardDeadReckonComponent)c;

      if ( fdrc.id == notNeededComponentId )
      {
        //  Redundant NOT which has been subsumed into a NAND/NOR
        continue;
      }

      int id = fdrc.id & 0xFFFFFF;
      boolean outputInverted = ((fdrc.id & componentIdOutputInvertedFlag) != 0);

      //debugComponentMap[id/4] = c;

      Collection<? extends PolymorphicComponent> outputs = c.getOutputs();
      Collection<? extends PolymorphicComponent> inputs = c.getInputs();
      if ( c instanceof PolymorphicProposition )
      {
        int moveIndex = ((ForwardDeadReckonProposition)c).getAssociatedTriggerIndex();
        if( moveIndex >= 0 )
        {
          componentType = componentTypeProposition;
          componentAssociatedTriggerIndexes[id/4] = (moveIndex<<1) | 1;
        }
        else
        {
          componentType = componentTypeNonTriggeringProposition;
        }
      }
      else if ( c instanceof PolymorphicTransition )
      {
        componentType = componentTypeTransition;
        componentAssociatedTriggerIndexes[id/4] = ((ForwardDeadReckonTransition)c).getAssociatedPropositionIndex()<<1;
      }
      else if ( c instanceof PolymorphicOr )
      {
        componentType = componentTypeOr;
      }
      else if ( c instanceof PolymorphicAnd )
      {
        componentType = componentTypeAnd;
      }
      else if ( c instanceof PolymorphicNot )
      {
        componentType = componentTypeNot;
      }
      else if ( c instanceof PolymorphicConstant )
      {
        if ( c.getValue() )
        {
          componentType = componentTypeTrueConstant;
        }
        else
        {
          componentType = componentTypeFalseConstant;
        }
      }

      //  If we subsumed a NOT into a universal logic component then it's that NOT's
      //  outputs that are the outputs of the universal component
      if ( outputInverted )
      {
        outputs = c.getSingleOutput().getOutputs();
      }

      int outputTypeBits = 0;
      boolean outTypeSet = false;
      boolean outTypeClash = false;

      for(PolymorphicComponent output : outputs)
      {
        if ( !outTypeSet )
        {
          outTypeSet = true;
          if ( (output instanceof PolymorphicOr) || (output instanceof PolymorphicAnd) || (output instanceof PolymorphicNot) ||
              (output instanceof PolymorphicProposition && ((ForwardDeadReckonProposition)output).getAssociatedTriggerIndex() == -1) )
          {
            outputTypeBits = componentIdOutputUniversalLogicBits;
          }
        }
        else
        {
          if ( (output instanceof PolymorphicOr) || (output instanceof PolymorphicAnd) || (output instanceof PolymorphicNot) ||
              (output instanceof PolymorphicProposition && ((ForwardDeadReckonProposition)output).getAssociatedTriggerIndex() == -1) )
          {
            if ( outputTypeBits != componentIdOutputUniversalLogicBits)
            {
              outputTypeBits = 0;
              outTypeClash = true;
              break;
            }
          }
          else if ( outputTypeBits != 0 )
          {
            outputTypeBits = 0;
            outTypeClash = true;
            break;
          }
        }
      }

      int numOutputs = outputs.size();
      int dummyOrId = -1;

      if ( outTypeClash )
      {
        //  Insert pass-through 1-input OR
        Set<PolymorphicComponent> movedTriggers = addedPassThroughComponents.get(id);

        dummyOrId = id - 4*((5 + movedTriggers.size())/4);

        int dummyComponentIndex = dummyOrId;

        componentDataTable[dummyComponentIndex++] = (1 | componentTypeOrBits);  // 1-input OR
        componentDataTable[dummyComponentIndex++] = movedTriggers.size();       // Number of outputs

        for(PolymorphicComponent output : movedTriggers)
        {
          assert(outputs.contains(output));
          componentDataTable[dummyComponentIndex++] = ((ForwardDeadReckonComponent)output).id;
        }

        //  Original component now outputs to the remaining subset of its original
        //  outputs plus the dummy OR
        numOutputs -= (movedTriggers.size()-1);
      }

      componentDataTable[id++] = (componentType << componentMetaDataTypeShift) |
                                 (outputInverted ? componentMetaDataOutputInverted : 0) |
                                 inputs.size();
      componentDataTable[id++] = numOutputs;

      for(PolymorphicComponent output : outputs)
      {
        if ( !outTypeClash ||
             (output instanceof PolymorphicOr) ||
             (output instanceof PolymorphicAnd) ||
             (output instanceof PolymorphicNot) ||
             (output instanceof PolymorphicProposition && ((ForwardDeadReckonProposition)output).getAssociatedTriggerIndex() == -1))
        {
          componentDataTable[id++] = ((ForwardDeadReckonComponent)output).id;
        }
      }
      if ( dummyOrId != -1 )
      {
        //  Add the dummy OR to the original's output list
        componentDataTable[id++] = dummyOrId | (componentTypeUniversalLogic << 24);
      }
    }
  }

  /**
   * Set the components ids of the sub-network rooted in a specified component, stopping at
   * transitions, in such a way as to increase the probability of consecutive numbering of outputs of each
   * component (which helps cache locality and stride predictability)
   * @param c root component to start from (must already have had its id set)
   * @param addedPassThroughComponents set of any pseudo-components added during numbering
    */
  private void recursivelySetComponentOutputIds(PolymorphicComponent c, Map<Integer, Set<PolymorphicComponent>> addedPassThroughComponents)
  {
    assert(((ForwardDeadReckonComponent)c).id != notSetComponentId);

    Set<PolymorphicComponent> processedOutputs = new HashSet<>();

    for(PolymorphicComponent output : c.getOutputs())
    {
      ForwardDeadReckonComponent fdrc = (ForwardDeadReckonComponent)output;
      if ( fdrc.id == notSetComponentId )
      {
        setComponentId(output, addedPassThroughComponents);
        processedOutputs.add(output);
      }
    }

    for(PolymorphicComponent output : processedOutputs)
    {
      if ( !(output instanceof PolymorphicTransition))
      {
        recursivelySetComponentOutputIds(output, addedPassThroughComponents);
      }
    }
  }

  /**
   * Set the component id of a specified component
   * @param c  component to set the id of
   * @param addedPassThroughComponents set of any pseudo-components added during numbering
   */
  private void setComponentId(PolymorphicComponent c, Map<Integer, Set<PolymorphicComponent>> addedPassThroughComponents)
  {
    ForwardDeadReckonComponent fdrc = (ForwardDeadReckonComponent)c;
    Collection<? extends PolymorphicComponent> outputs = fdrc.getOutputs();
    boolean hasPropagatableOutputs = !outputs.isEmpty();
    boolean hasTrigger = false;
    boolean outputsInverted = false;
     int componentType = 0;

    if ( c instanceof PolymorphicProposition )
    {
      hasTrigger = (((ForwardDeadReckonProposition)c).getAssociatedTriggerIndex() != -1);
      //  We don't expect legal moves to have outputs!
      assert(!hasTrigger || !hasPropagatableOutputs);

      if ( hasTrigger )
      {
        componentType = componentTypeProposition;
      }
      else
      {
        //  Represent non-triggering propositions (goals and terminal essentially)
        //  as 1-input ORs just to hold the necessary state.  This allows us to divide
        //  the network into strictly logic or triggering components and eliminates the
        //  need to check that a trigger is present on a supposedly triggering type
        componentType = componentTypeUniversalLogic;
      }
    }
    else if ( c instanceof PolymorphicTransition )
    {
      componentType = componentTypeTransition;
      hasPropagatableOutputs = false;
      hasTrigger = (((ForwardDeadReckonTransition)c).getAssociatedPropositionIndex() != -1);
      //  Transitions should always have triggers (else what are they doing?)
      assert(hasTrigger);
    }
    else if ( (c instanceof PolymorphicOr) || (c instanceof PolymorphicAnd) )
    {
      componentType = componentTypeUniversalLogic;

      //  If it has a single output to a NOT roll the NOT into this component and a NAND/NOR
      if ( outputs.size() == 1 )
      {
        PolymorphicComponent output = c.getSingleOutput();

        if ( output instanceof PolymorphicNot )
        {
          outputs = output.getOutputs();
          outputsInverted = true;
        }
      }
    }
    else if ( c instanceof PolymorphicNot )
    {
      //  Check this isn't a single output of an AND/OR in which case the AND/OR will be processed as
      //  a NAND/NOR and this component need not be processed
      PolymorphicComponent input = c.getSingleInput();
      if ( ((input instanceof PolymorphicOr) || (input instanceof PolymorphicAnd)) &&
           input.getOutputs().size() == 1 )
      {
        fdrc.id = notNeededComponentId;
        return;
      }

      componentType = componentTypeUniversalLogic;
    }
    else if ( c instanceof PolymorphicConstant )
    {
      if ( c.getValue() )
      {
        componentType = componentTypeTrueConstant;
      }
      else
      {
        componentType = componentTypeFalseConstant;
      }
    }

    int outputTypeBits = 0;
    boolean outTypeSet = false;
    boolean outTypeClash = false;

    //  Check that all outputs are of the same type (either represented in our fast propagator
    //  tables as universal logic elements or as triggers, but not both)
    for(PolymorphicComponent output : outputs)
    {
      if ( !outTypeSet )
      {
        outTypeSet = true;
        if ( (output instanceof PolymorphicOr) || (output instanceof PolymorphicAnd) || (output instanceof PolymorphicNot) ||
             (output instanceof PolymorphicProposition && ((ForwardDeadReckonProposition)output).getAssociatedTriggerIndex() == -1))
        {
          outputTypeBits = componentIdOutputUniversalLogicBits;
        }
      }
      else
      {
        if ( (output instanceof PolymorphicOr) || (output instanceof PolymorphicAnd) || (output instanceof PolymorphicNot) ||
            (output instanceof PolymorphicProposition && ((ForwardDeadReckonProposition)output).getAssociatedTriggerIndex() == -1))
        {
          if ( outputTypeBits != componentIdOutputUniversalLogicBits)
          {
            outputTypeBits = 0;
            outTypeClash = true;
            break;
          }
        }
        else if ( outputTypeBits != 0 )
        {
          outputTypeBits = 0;
          outTypeClash = true;
          break;
        }
      }
    }

    int numOutputs = outputs.size();

    if ( outTypeClash )
    {
      //  Insert a dummy pass-through (1-input OR) component to restore
      //  strict layering
      Set<PolymorphicComponent> movedTriggers = new HashSet<>();

      for(PolymorphicComponent output : outputs)
      {
        if ( (output instanceof PolymorphicProposition && ((ForwardDeadReckonProposition)output).getAssociatedTriggerIndex() != -1) || (output instanceof PolymorphicTransition) )
        {
          movedTriggers.add(output);
        }
      }

      numOutputs -= (movedTriggers.size()-1);

      outputTypeBits = componentIdOutputUniversalLogicBits;

      nextComponentBaseId += 4*((movedTriggers.size()+5)/4);

      addedPassThroughComponents.put(nextComponentBaseId, movedTriggers);
    }

    fdrc.id = nextComponentBaseId |
              (componentType << 24) |
               outputTypeBits |
              (outputsInverted ? componentIdOutputInvertedFlag : 0);

    nextComponentBaseId += 4*((numOutputs+5)/4);
    assert(nextComponentBaseId < 0x01000000);
  }

  /**
   * Crystalize the number of instances that will be required at simulation-time
   * This must be called before any simulation is attempted and may only be called once
   *
   * @param numInstances Number of separate thread-safe instances to support
   */
  public void crystalize(int numInstances)
  {
    instances = new InstanceInfo[numInstances];

    for(int i = 0; i < numInstances; i++)
    {
      instances[i] = new InstanceInfo(nextComponentBaseId/4);
      instances[i].legalMoveNotifier = propNet.getActiveLegalProps(i);
      instances[i].propositionTransitionNotifier = propNet.getActiveBaseProps(i);
    }
  }

  /**
   * Retrieve the InstanceInfo for a given instance id
   * @param instanceId id to retrieve the state structure for
   * @return state structure for the specified instance
   */
  public InstanceInfo getInstanceInfo(int instanceId)
  {
    return instances[instanceId];
  }

  /**
   * Reset the network to its default state (all inputs to all
   * components assumed FALSE).  If a full reset is performed
   * then any components whose outputs are TRUE will be propagated
   * to arrive at a globally consistent state
   * @param instanceId id of the instance to reset
   * @param fullPropagate whether to propagate to full global consistency
   */
  public void reset(int instanceId, boolean fullPropagate)
  {
    InstanceInfo instanceInfo = instances[instanceId];
    int[] state = instanceInfo.state;
    int i = 0;

    while(i < nextComponentBaseId)
    {
      int stateEntryIndex = i/4;
      //  Retrieve the actual component type - note that we MUST retrieve this from
      //  the component info NOT its id, since the id representation uses the same
      //  code for AND and OR and encodes NAND and NOR as well
      int compMetaData = componentDataTable[i];
      int numOutputs = componentDataTable[i+1];
      int componentTypeBits = (compMetaData & componentMetaDataTypeMask);
      boolean outputInverted = ((compMetaData & componentMetaDataOutputInverted) != 0);

      i += 4*((numOutputs + 5)/4);

      switch(componentTypeBits)
      {
        case componentTypePropositionBits:
        case componentTypeTransitionBits:
        case componentTypeFalseConstantBits:
          state[stateEntryIndex] = 0;
          break;
        case componentTypeNonTriggeringPropositionBits:
        case componentTypeOrBits:
          if ( outputInverted )
          {
            state[stateEntryIndex] = 0xFFFFFFFF;
          }
          else
          {
            state[stateEntryIndex] = 0x7FFFFFFF;
          }
          break;
        case componentTypeAndBits:
          int   numInputs = (compMetaData & componentMetaDataInputCountMask);
          if ( outputInverted )
          {
            state[stateEntryIndex] = -numInputs;
          }
          else
          {
            state[stateEntryIndex] = 0x80000000 - numInputs;
          }
          break;
        case componentTypeNotBits:
          state[stateEntryIndex] = 0xFFFFFFFF;
          break;
        case componentTypeTrueConstantBits:
          state[stateEntryIndex] = componentStateCachedValMask;
          break;
        default:
          break;
      }
    }

    if ( fullPropagate )
    {
      i = 0;

      while(i < nextComponentBaseId)
      {
        instanceInfo.resetWatermark = i;

        int stateEntryIndex = i/4;
        //  Retrieve the actual component type - note that we MUST retrieve this from
        //  the component info NOT its id, since the id representation uses the same
        //  code for AND and OR and encodes NAND and NOR as well
        int compMetaData = componentDataTable[i];
        int numOutputs = componentDataTable[i+1];

        if ( numOutputs > 0 )
        {
          boolean current = ((state[stateEntryIndex] & componentStateCachedValMask) != 0);
          int type = ((compMetaData & componentMetaDataTypeMask) >> componentMetaDataTypeShift);

          if ( current && type != componentTypeTransition )
          {
            propagateComponent(instanceInfo, instanceInfo.resetWatermark, current);
          }
        }

        i += 4*((numOutputs + 5)/4);
      }
    }
    else
    {
      instanceInfo.resetWatermark = nextComponentBaseId;
    }
  }

  private void propagateComponentTrue(InstanceInfo instanceInfo, int componentIdFull)
  {
    int[] state = instanceInfo.state;
    int   outputIndex = (componentIdFull & 0xFFFFFF)+1;
    int   numOutputs = componentDataTable[outputIndex++];
    //PolymorphicComponent c = debugComponentMap[(componentIdFull & 0xFFFFFF)/4];

    if ((componentIdFull & componentIdOutputUniversalLogicBits) == 0)
    {
      while(numOutputs-- > 0)
      {
        int outputId = (componentDataTable[outputIndex++] & 0xFFFFFF)>>2;

        state[outputId] |= componentStateCachedValMask;

        int triggerIndex = componentAssociatedTriggerIndexes[outputId];
        if ( (triggerIndex & 1) != 0 )
        {
          instanceInfo.legalMoveNotifier.add(triggerIndex>>1);
        }
        else
        {
          instanceInfo.propositionTransitionNotifier.add(triggerIndex>>1);
        }
      }
    }
    else
    {
      while(numOutputs-- > 0)
      {
        int outputFullId = componentDataTable[outputIndex++];

        int stateVal = ++state[(outputFullId & 0xFFFFFF)>>2];
        if ( (stateVal & 0x7FFFFFFF) == 0 )
        {
          if (stateVal != 0)
          {
            propagateComponentTrue(instanceInfo, outputFullId);
          }
          else
          {
            propagateComponentFalse(instanceInfo, outputFullId);
          }
        }
      }
    }
  }

  private void propagateComponentFalse(InstanceInfo instanceInfo, int componentIdFull)
  {
    int[] state = instanceInfo.state;
    int   outputIndex = (componentIdFull & 0xFFFFFF)+1;
    int   numOutputs = componentDataTable[outputIndex++];
    //PolymorphicComponent c = debugComponentMap[(componentIdFull & 0xFFFFFF)/4];

    if ((componentIdFull & componentIdOutputUniversalLogicBits) == 0)
    {
      while(numOutputs-- > 0)
      {
        int outputId = (componentDataTable[outputIndex++] & 0xFFFFFF)>>2;

        state[outputId] &= ~componentStateCachedValMask;

        int triggerIndex = componentAssociatedTriggerIndexes[outputId];
        if ( (triggerIndex & 1) != 0 )
        {
          instanceInfo.legalMoveNotifier.remove(triggerIndex>>1);
        }
        else
        {
          instanceInfo.propositionTransitionNotifier.remove(triggerIndex>>1);
        }
      }
    }
    else
    {
      while(numOutputs-- > 0)
      {
        int outputFullId = componentDataTable[outputIndex++];

        int stateVal = --state[(outputFullId & 0xFFFFFF)>>2];
        if ( (stateVal & 0x7FFFFFFF) == 0x7FFFFFFF )
        {
          if ( stateVal >= 0 )
          {
            propagateComponentFalse(instanceInfo, outputFullId);
          }
          else
          {
            propagateComponentTrue(instanceInfo, outputFullId);
          }
        }
      }
    }
  }


  private void propagateComponent(InstanceInfo instanceInfo, int componentId, boolean value)
  {
    assert((componentId & 0xFFFFFF)%4 == 0);

    if ( instanceInfo.resetWatermark >= (componentId & 0xFFFFFF) )
    {
      int[] state = instanceInfo.state;
      int   outputIndex = (componentId & 0xFFFFFF)+1;
      int   numOutputs = componentDataTable[outputIndex++];
      int outputIdBits;

      while(numOutputs-- > 0)
      {
        int outputFullId = componentDataTable[outputIndex+numOutputs];
        int outputId = (outputFullId & 0xFFFFFF)/4;
        outputIdBits = outputFullId & componentIdTypeMask;

        switch(outputIdBits)
        {
          case componentIdPropositionBits:
            if ( value )
            {
              state[outputId] |= componentStateCachedValMask;
            }
            else
            {
              state[outputId] &= ~componentStateCachedValMask;
            }

            int moveIndex = componentAssociatedTriggerIndexes[outputId];

            if ( moveIndex != -1 )
            {
              if ( value )
              {
                instanceInfo.legalMoveNotifier.add(moveIndex/2);
              }
              else
              {
                instanceInfo.legalMoveNotifier.remove(moveIndex/2);
              }
            }

            propagateComponent(instanceInfo, outputFullId, value);
            break;
          case componentIdTransitionBits:
            if ( value )
            {
              state[outputId] |= componentStateCachedValMask;
            }
            else
            {
              state[outputId] &= ~componentStateCachedValMask;
            }

            int propIndex = componentAssociatedTriggerIndexes[outputId];

            if ( value )
            {
              instanceInfo.propositionTransitionNotifier.add(propIndex/2);
            }
            else
            {
              instanceInfo.propositionTransitionNotifier.remove(propIndex/2);
            }
            break;
          case componentIdUniversalLogicBits:
             int stateVal;
            if ( value )
            {
              stateVal = ++state[outputId];
              if ( stateVal == 0x80000000 )
              {
                propagateComponent(instanceInfo, outputFullId, true);
              }
              else if ( stateVal == 0 )
              {
                propagateComponent(instanceInfo, outputFullId, false);
              }
            }
            else
            {
              stateVal = --state[outputId];
              if ( stateVal == 0x7FFFFFFF )
              {
                propagateComponent(instanceInfo, outputFullId, false);
              }
              else if ( stateVal == 0xFFFFFFFF )
              {
                propagateComponent(instanceInfo, outputFullId, true);
              }
            }
            break;
          default:
            //  Should not happen
            throw new UnsupportedOperationException("Unexpected component type");
        }
      }
    }
  }

  /**
   * Change the value of an input proposition, asserting that the new value IS a change
   * @param instanceInfo  instance to operate on
   * @param propId        component whose value is being changed
   * @param value         new value - this MUST be !<old value> but this is NOT checked
   */
  public void changeComponentValueTo(InstanceInfo instanceInfo, int propId, boolean value)
  {
    int[] state = instanceInfo.state;
    int compId = (propId & 0xFFFFFF)>>2;

    if ( value )
    {
      state[compId] |= componentStateCachedValMask;
      propagateComponentTrue(instanceInfo, propId);
    }
    else
    {
      state[compId] &= ~componentStateCachedValMask;
      propagateComponentFalse(instanceInfo, propId);
    }
  }

  /**
   * Set the value of an input proposition
   * @param instanceId    instance to operate on
   * @param propId        component whose value is being changed
   * @param value         new value
   */
  public void setComponentValue(int instanceId, int propId, boolean value)
  {
    InstanceInfo instanceInfo = instances[instanceId];
    int[] state = instanceInfo.state;
    int compId = (propId & 0xFFFFFF)>>2;
    boolean currentValue = ((state[compId] & componentStateCachedValMask) != 0);

    if ( value != currentValue )
    {
      if ( value )
      {
        state[compId] |= componentStateCachedValMask;
        propagateComponentTrue(instanceInfo, propId);
      }
      else
      {
        state[compId] &= ~componentStateCachedValMask;
        propagateComponentFalse(instanceInfo, propId);
      }
    }
  }

  /**
   * Retrieve the current state of a proposition
   * @param instanceId id of the instance to retrieve from
   * @param propId id of the proposition to retrieve the value of
   * @return the proposition's current value
   */
  public boolean getComponentValue(int instanceId, int propId)
  {
    int[] state = instances[instanceId].state;
    return ((state[(propId & 0xFFFFFF)>>2] & componentStateCachedValMask) != 0);
  }
}
