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
    }

    void propagateComponentTrue(int componentIdFull)
    {
      int   outputIndex = (componentIdFull & 0xFFFFFF)+1;
      int   numOutputs = componentDataTable[outputIndex++];

      //  Test componentIdOutputUniversalLogicBits - this is the sign bit (specifically)
      //  which is faster to test with a direct sign test
      if (componentIdFull >= 0)
      {
        do
        {
          int triggerIndex = componentDataTable[outputIndex++];

          if ( triggerIndex < 0 )
          {
            legalMoveNotifier.add(triggerIndex & 0x7FFFFFFF);
          }
          else
          {
            propositionTransitionNotifier.add(triggerIndex);
          }
        } while(--numOutputs > 0);
      }
      else
      {
        while(numOutputs-- > 0)
        {
          int outputFullId = componentDataTable[outputIndex++];

          int stateVal = ++state[(outputFullId & 0xFFFFFF)>>2];
          if ( stateVal == 0 )
          {
            propagateComponentFalse(outputFullId);
          }
          else if ( stateVal == 0x80000000 )
          {
            propagateComponentTrue(outputFullId);
          }
        }
      }
    }

    void propagateComponentFalse(int componentIdFull)
    {
      int   outputIndex = (componentIdFull & 0xFFFFFF)+1;
      int   numOutputs = componentDataTable[outputIndex++];

      //  Test componentIdOutputUniversalLogicBits - this is the sign bit (specifically)
      //  which is faster to test with a direct sign test
      if (componentIdFull >= 0)
      {
        do
        {
          int triggerIndex = componentDataTable[outputIndex++];

          if ( triggerIndex < 0 )
          {
            legalMoveNotifier.remove(triggerIndex & 0x7FFFFFFF);
          }
          else
          {
            propositionTransitionNotifier.remove(triggerIndex);
          }
        } while(--numOutputs > 0);
      }
      else
      {
        while(numOutputs-- > 0)
        {
          int outputFullId = componentDataTable[outputIndex++];

          int stateVal = state[(outputFullId & 0xFFFFFF)>>2]--;
          if ( stateVal == 0 )
          {
            propagateComponentTrue(outputFullId);
          }
          else if ( stateVal == 0x80000000 )
          {
            propagateComponentFalse(outputFullId);
          }
        }
      }
    }

    void propagateComponent(int componentId, boolean value)
    {
      assert((componentId & 0xFFFFFF)%4 == 0);

      if ( resetWatermark >= (componentId & 0xFFFFFF) )
      {
        int   outputIndex = (componentId & 0xFFFFFF)+1;
        int   numOutputs = componentDataTable[outputIndex++];
        int outputIdBits;
        boolean isTrigger = (componentId >= 0);//(componentId & componentIdOutputUniversalLogicBits) == 0;

        while(numOutputs-- > 0)
        {
          int outputFullId = componentDataTable[outputIndex+numOutputs];

          if ( !isTrigger )
          {
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

                propagateComponent(outputFullId, value);
                break;
              case componentIdTransitionBits:
                assert(false);
                break;
              case componentIdUniversalLogicBits:
                int stateVal;
                if ( value )
                {
                  stateVal = ++state[outputId];
                  if ( stateVal == 0x80000000 )
                  {
                    propagateComponent( outputFullId, true);
                  }
                  else if ( stateVal == 0 )
                  {
                    propagateComponent(outputFullId, false);
                  }
                }
                else
                {
                  stateVal = --state[outputId];
                  if ( stateVal == 0x7FFFFFFF )
                  {
                    propagateComponent(outputFullId, false);
                  }
                  else if ( stateVal == 0xFFFFFFFF )
                  {
                    propagateComponent(outputFullId, true);
                  }
                }
                break;
              default:
                //  Should not happen
                throw new UnsupportedOperationException("Unexpected component type");
            }
          }
          else
          {
            int moveIndex = outputFullId;
            //int moveIndex = outputId;

            {
              if ( value )
              {
                if ( moveIndex < 0 )
                {
                  legalMoveNotifier.add(moveIndex & 0x7FFFFFFF);
                }
                else
                {
                  propositionTransitionNotifier.add(moveIndex);
                }
              }
              else
              {
                if ( moveIndex < 0 )
                {
                  legalMoveNotifier.remove(moveIndex & 0x7FFFFFFF);
                }
                else
                {
                  propositionTransitionNotifier.remove(moveIndex);
                }
              }
//              if ( value )
//              {
//                state[outputId] |= componentStateCachedValMask;
//              }
//              else
//              {
//                state[outputId] &= ~componentStateCachedValMask;
//              }
            }
          }
        }
      }
    }

    /**
     * Change the value of an input proposition, asserting that the new value IS a change
     * @param propId        component whose value is being changed
     * @param value         new value - this MUST be !<old value> but this is NOT checked
     */
    public void changeComponentValueTo(int propId, boolean value)
    {
      int compId = (propId & 0xFFFFFF)>>2;

      if ( value )
      {
        state[compId] |= componentStateCachedValMask;
        //propagateComponentTrueOrFalse(instanceInfo, propId, 1);
        propagateComponentTrue(propId);
      }
      else
      {
        state[compId] &= ~componentStateCachedValMask;
        //propagateComponentTrueOrFalse(instanceInfo, propId, -1);
        propagateComponentFalse(propId);
      }
    }

    /**
     * Set the value of an input proposition
     * @param instanceId    instance to operate on
     * @param propId        component whose value is being changed
     * @param value         new value
     */
    public void setComponentValue(int propId, boolean value)
    {
      int compId = (propId & 0xFFFFFF)>>2;
      boolean currentValue = ((state[compId] & componentStateCachedValMask) != 0);

      if ( value != currentValue )
      {
        if ( value )
        {
          state[compId] |= componentStateCachedValMask;
          propagateComponentTrue(propId);
        }
        else
        {
          state[compId] &= ~componentStateCachedValMask;
          propagateComponentFalse(propId);
        }
      }
    }
    /**
     * Vector of state values for each component indexed by component id
     */
    final int[]                                   state;
    /**
     * Interface to call for changes to the output state of legal move props
     */
    ForwardDeadReckonLegalMoveSet                 legalMoveNotifier;
    /**
     * Interface to call for changes to the output state of transitions
     */
    ForwardDeadReckonInternalMachineState         propositionTransitionNotifier;
    //final ForwardDeadReckonComponentTransitionNotifier[] notifiers = new ForwardDeadReckonComponentTransitionNotifier[2];
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
  final int[]                             componentDataTable;

  private static final int                componentMetaDataTypeMask = 0x07000000; //  3-bits for component type
  private static final int                componentMetaDataTypeShift = 24;
  private static final int                componentMetaDataOutputInverted = 0x08000000; //  Flag to indicate output is inverted (only applies to AND/OR)
  private static final int                componentMetaDataOutputUniversalLogic = 0x10000000; //  Flag to indicate output is more logic (not trigger)
  private static final int                componentMetaDataInputCountMask = 0xFFFF; // Num inputs mask (16 bits)

  //  Component types
  private static final int                componentTypeNonTriggeringProposition = 7;
  private static final int                componentTypeProposition = 1;
  private static final int                componentTypeTransition = 0;
  private static final int                componentTypeOr = 2;
  //  Internally in the runtime representation AND/OR/NAND/NOR and NOT are all combined
  //  in a single universal logic component.  Because it doesn't exist in the same representations
  //  as the basic logic components it can use an id that overlaps with them, so we can keep types down to 8
  private static final int                componentTypeUniversalLogic = 2;
  private static final int                componentTypeAnd = 3;
  private static final int                componentTypeNot = 4;
  private static final int                componentTypeTrueConstant = 5;
  private static final int                componentTypeFalseConstant = 6;
  private static final int                componentTypePropositionBits = (componentTypeProposition << componentMetaDataTypeShift);
  private static final int                componentTypeTransitionBits = (componentTypeTransition << componentMetaDataTypeShift);
  private static final int                componentTypeOrBits = (componentTypeOr << componentMetaDataTypeShift);
  private static final int                componentTypeAndBits = (componentTypeAnd << componentMetaDataTypeShift);
  private static final int                componentTypeNotBits = (componentTypeNot << componentMetaDataTypeShift);
  private static final int                componentTypeTrueConstantBits = (componentTypeTrueConstant << componentMetaDataTypeShift);
  private static final int                componentTypeFalseConstantBits = (componentTypeFalseConstant << componentMetaDataTypeShift);
  private static final int                componentTypeNonTriggeringPropositionBits = (componentTypeNonTriggeringProposition << componentMetaDataTypeShift);

  //  We us the top byte of the component id to encode some commonly required
  //  features so as to avoid the need for a lookup
  private static final int                componentIdTypeMask = 0x07000000;
  private static final int                componentIdTypeShift = 24;
  private static final int                componentIdPropositionBits = (componentTypeProposition << componentIdTypeShift);
  private static final int                componentIdTransitionBits = (componentTypeTransition << componentIdTypeShift);
  private static final int                componentIdUniversalLogicBits = (componentTypeUniversalLogic << componentIdTypeShift);
  private static final int                componentIdOutputInvertedFlag = (1<<29);
  private static final int                componentIdOutSingleTrigger = (1<<30);
  private static final int                componentIdOutputUniversalLogicBits = (1 << 31);

  // states will be represented by per-thread int arrays, with each int packed as follows
  private static final int                componentStateCachedValMask = 1<<31; // Current state
  // Remaining bits are free for opaque use by the implementation.  For triggers (props with associated
  // legals or transitions) they are unused.  For the universal logic element it is a count which is
  // incremented on an input transitioning to TRUE, and decremented for a transition to FALSE.  These
  // increments/decrements are deliberately allowed to overflow into the sign bit, which is the state mask
  // This allows a single inc/dec to operate the components as any of OR/AND/NOR/NAND depending on how
  // reset initializes the value

  //  Reserved id used to denote a component whose id has not yet been set
  private static final int notSetComponentId = -2;
  //  Reserved id used to denote a component which has been subsumed into another
  //  component for fast propagation
  public static final int notNeededComponentId = -1;

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
      if ( c.getOutputs().isEmpty() && c.getInputs().isEmpty() )//thePropNet.getBasePropositions().values().contains(c) )
      {
        ((ForwardDeadReckonComponent)c).id = notNeededComponentId;
      }
      else
      {
        ((ForwardDeadReckonComponent)c).id = notSetComponentId;
      }
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

      Collection<? extends PolymorphicComponent> outputs = c.getOutputs();
      Collection<? extends PolymorphicComponent> inputs = c.getInputs();
      if ( c instanceof PolymorphicProposition )
      {
        int moveIndex = ((ForwardDeadReckonProposition)c).getAssociatedTriggerIndex();
        if( moveIndex >= 0 )
        {
          componentType = componentTypeProposition;
        }
        else
        {
          componentType = componentTypeNonTriggeringProposition;
        }
      }
      else if ( c instanceof PolymorphicTransition )
      {
        componentType = componentTypeTransition;
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
              outputTypeBits = componentIdOutputUniversalLogicBits;
              outTypeClash = true;
              break;
            }
          }
          else if ( outputTypeBits != 0 )
          {
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

        int typeInfo = (1 | componentTypeOrBits);  // 1-input OR;
        if ( movedTriggers.size() == 1 )
        {
          typeInfo |= componentIdOutSingleTrigger;
        }
        componentDataTable[dummyComponentIndex++] = typeInfo;
        componentDataTable[dummyComponentIndex++] = movedTriggers.size();       // Number of outputs

        for(PolymorphicComponent output : movedTriggers)
        {
          int triggerId = getTriggerId(output);

          assert(outputs.contains(output));

          componentDataTable[dummyComponentIndex++] = triggerId;
          //componentDataTable[dummyComponentIndex++] = ((ForwardDeadReckonComponent)output).id;
        }

        //  Original component now outputs to the remaining subset of its original
        //  outputs plus the dummy OR
        numOutputs -= (movedTriggers.size()-1);
      }

      if (numOutputs == 0 )
      {
        outputTypeBits |= componentIdOutputUniversalLogicBits;
      }

      componentDataTable[id++] = (componentType << componentMetaDataTypeShift) |
                                 (outputInverted ? componentMetaDataOutputInverted : 0) |
                                 (((outputTypeBits & componentIdOutputUniversalLogicBits) != 0) ? componentMetaDataOutputUniversalLogic : 0) |
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
          if ( (outputTypeBits & componentIdOutputUniversalLogicBits) == 0 )
          {
            componentDataTable[id++] = getTriggerId(output);
          }
          else
          {
            assert(((ForwardDeadReckonComponent)output).id != notNeededComponentId);
            assert(((ForwardDeadReckonComponent)output).id != notSetComponentId);
            componentDataTable[id++] = ((ForwardDeadReckonComponent)output).id;
          }
        }
      }
      if ( dummyOrId != -1 )
      {
        //  Add the dummy OR to the original's output list
        componentDataTable[id++] = dummyOrId | (componentTypeUniversalLogic << 24);
      }
    }
  }

  private static int getTriggerId(PolymorphicComponent c)
  {
    if ( c instanceof PolymorphicProposition )
    {
      int moveIndex = ((ForwardDeadReckonProposition)c).getAssociatedTriggerIndex();
      if( moveIndex >= 0 )
      {
        return moveIndex | 0x80000000;
      }

      assert(false) : "Attempt to retrieve trigger id of non-triggering proposition";
    }
    else if ( c instanceof PolymorphicTransition )
    {
      return ((ForwardDeadReckonTransition)c).getAssociatedPropositionIndex();
    }
    else
    {
      assert(false) : "Unexpected attempt to retrieve trigger id on non-trigger component";
    }

    return -1;
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

    if (numOutputs == 0 )
    {
      outputTypeBits |= componentIdOutputUniversalLogicBits;
    }

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
    else if ( outputTypeBits == 0 && numOutputs == 1 )
    {
      outputTypeBits = componentIdOutSingleTrigger;
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
      //instances[i].notifiers[0] = instances[i].propositionTransitionNotifier;
      //instances[i].notifiers[1] = instances[i].legalMoveNotifier;
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
            int fullId = instanceInfo.resetWatermark;
            if ( (compMetaData & componentMetaDataOutputUniversalLogic) != 0 )
            {
              fullId |= componentIdOutputUniversalLogicBits;
            }

            instanceInfo.propagateComponent(fullId, current);
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
