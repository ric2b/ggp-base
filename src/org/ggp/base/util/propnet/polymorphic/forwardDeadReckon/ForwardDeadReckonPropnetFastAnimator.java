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
     * representatio of the network
     */
    InstanceInfo(int numComponents)
    {
      state = new int[numComponents];
    }
    /**
     * Vector of state values for each component indexed by component id
     */
    final int[]                                   state;
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

  private final ForwardDeadReckonPropNet propNet;
  private final long[]             componentInfo;
  //  each long is a packed record using the following masks:
  private final long               componentInfoTypeMask = 0x07000000L; //  3-bits for component type
  private final int                componentInfoTypeShift = 24;
  private final long               componentInfoOutputInverted = 0x08000000L; //  Flag to indicate output is inverted (only applies to AND/OR)
  private final long               componentInfoOutputCountMask = 0xFFFF00000000L; // Num outputs mask (16 bits)
  private final int                componentInfoOutputCountShift = 32;
  private final long               componentInfoConnectivityOffsetMask = 0xFFFFFFL; // Start offset of output indexes in connectivity array
  private final int                componentInfoConnectivityOffsetShift = 0;
  private final long               componentInfoInputCountMask = 0xFFFF000000000000L; // Num inputs mask (16 bits)
  private final int                componentInfoInputCountShift = 48;

  //  Array indexed by component id containing the index to call either the legal move
  //  or proposition transition trigger handler with.  In either case the value stored is the
  //  desired index shifted left 1 bit, and with 1 ORd in to flag the legal move case
  private final int[]              componentAssociatedTriggerIndexes;
  //  Array containing the ids of outputs to each component, packed consecutively.  The offset for
  //  the first entry for each component is stored in its componentInfo entry
  private final int[]              componentConnectivityTable;
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
  private final int                componentTypePropositionBits = (componentTypeProposition << componentInfoTypeShift);
  private final int                componentTypeTransitionBits = (componentTypeTransition << componentInfoTypeShift);
  private final int                componentTypeOrBits = (componentTypeOr << componentInfoTypeShift);
  private final int                componentTypeAndBits = (componentTypeAnd << componentInfoTypeShift);
  private final int                componentTypeNotBits = (componentTypeNot << componentInfoTypeShift);
  private final int                componentTypeTrueConstantBits = (componentTypeTrueConstant << componentInfoTypeShift);
  private final int                componentTypeFalseConstantBits = (componentTypeFalseConstant << componentInfoTypeShift);
  private final int                componentTypeNonTriggeringPropositionBits = (componentTypeNonTriggeringProposition << componentInfoTypeShift);

  //  We us the top byte of the component id to encode some commonly required
  //  features so as to avoid the need for a lookup to the detailed info long
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

  // Each instance (which typically maps to a thread) has its own InstanceInfo to hold the complete state
  // of that instance's propNet
  private InstanceInfo[]           instances;

  /**
   * Constructs a new fast animator for the given network.  The network must not be changed
   * after this call is made or the generated fast animator will no longer be valid
   *
   * @param thePropNet1 Network to construct an animator for
   */
  public ForwardDeadReckonPropnetFastAnimator(ForwardDeadReckonPropNet thePropNet1)
  {
    propNet = thePropNet1;

    int index = 0;
    int outputCount = 0;
    int componentType = 0;

    //  Dummy components are (virtually) added to ensure that all outputs from any
    //  particular component are either all universal logic elements or are triggers.
    //  In either case this allows the propagation methods to assume that all outputs are
    //  of  matching type, and loop through them without type-checks.
    //  Where the original (PolymorphicComponent) network has a component with outputs of
    //  mixed type, we add a dummy 1-input OR buffer to separate the triggers from the
    //  logic
    Map<Integer, Set<PolymorphicComponent>> addedPassThroughComponents = new HashMap<>();

    //  Number components and set up metadata flags stored in their high id bits
    for(PolymorphicComponent c : thePropNet1.getComponents())
    {
      ForwardDeadReckonComponent fdrc = (ForwardDeadReckonComponent)c;
      Collection<? extends PolymorphicComponent> outputs = c.getOutputs();
      boolean hasPropagatableOutputs = !outputs.isEmpty();
      boolean hasTrigger = false;
      boolean outputsInverted = false;

      outputCount += outputs.size();

      if ( c instanceof PolymorphicProposition )
      {
        hasTrigger = (((ForwardDeadReckonProposition)c).getAssociatedMoveIndex() != -1);
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
            outputCount += (outputs.size()-1);
            outputsInverted = true;
          }
        }
      }
      else if ( c instanceof PolymorphicNot )
      {
        //  Check this isn't a single output of an AND/OR in which cse the AND/OR will be processed as
        //  a NAND/NOR and this component need not be processed
        PolymorphicComponent input = c.getSingleInput();
        if ( ((input instanceof PolymorphicOr) || (input instanceof PolymorphicAnd)) &&
             input.getOutputs().size() == 1 )
        {
          fdrc.id = -1;
          continue;
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
               (output instanceof PolymorphicProposition && ((ForwardDeadReckonProposition)output).getAssociatedMoveIndex() == -1))
          {
            outputTypeBits = componentIdOutputUniversalLogicBits;
          }
        }
        else
        {
          if ( (output instanceof PolymorphicOr) || (output instanceof PolymorphicAnd) || (output instanceof PolymorphicNot) ||
              (output instanceof PolymorphicProposition && ((ForwardDeadReckonProposition)output).getAssociatedMoveIndex() == -1))
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

      if ( outTypeClash )
      {
        //  Insert a dummy pass-through (1-input OR) component to restore
        //  strict layering
        Set<PolymorphicComponent> movedTriggers = new HashSet<>();

        for(PolymorphicComponent output : outputs)
        {
          if ( (output instanceof PolymorphicProposition && ((ForwardDeadReckonProposition)output).getAssociatedMoveIndex() != -1) || (output instanceof PolymorphicTransition) )
          {
            movedTriggers.add(output);
          }
        }

        addedPassThroughComponents.put(index++, movedTriggers);
        outputTypeBits = componentIdOutputUniversalLogicBits;

        outputCount++;
      }

      fdrc.id = index++ |
                (componentType << 24) |
                outputTypeBits |
                (outputsInverted ? componentIdOutputInvertedFlag : 0);
    }

    componentInfo = new long[index];
    componentAssociatedTriggerIndexes = new int[index];
    componentConnectivityTable = new int[outputCount];
    int componentOffset = 0;

    for(PolymorphicComponent c : thePropNet1.getComponents())
    {
      ForwardDeadReckonComponent fdrc = (ForwardDeadReckonComponent)c;

      if ( fdrc.id == -1 )
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
        int moveIndex = ((ForwardDeadReckonProposition)c).getAssociatedMoveIndex();
        if( moveIndex >= 0 )
        {
          componentType = componentTypeProposition;
          componentAssociatedTriggerIndexes[id] = (moveIndex<<1) | 1;
        }
        else
        {
          componentType = componentTypeNonTriggeringProposition;
        }
      }
      else if ( c instanceof PolymorphicTransition )
      {
        componentType = componentTypeTransition;
        componentAssociatedTriggerIndexes[id] = ((ForwardDeadReckonTransition)c).getAssociatedPropositionIndex()<<1;
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
              (output instanceof PolymorphicProposition && ((ForwardDeadReckonProposition)output).getAssociatedMoveIndex() == -1) )
          {
            outputTypeBits = componentIdOutputUniversalLogicBits;
          }
        }
        else
        {
          if ( (output instanceof PolymorphicOr) || (output instanceof PolymorphicAnd) || (output instanceof PolymorphicNot) ||
              (output instanceof PolymorphicProposition && ((ForwardDeadReckonProposition)output).getAssociatedMoveIndex() == -1) )
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
        //  Insert pass-through 1-input OR
        Set<PolymorphicComponent> movedTriggers = addedPassThroughComponents.get(id-1);

        componentInfo[id-1] = ((long)componentOffset << componentInfoConnectivityOffsetShift) |
            ((long)movedTriggers.size() << componentInfoOutputCountShift) |
            ((long)1 << componentInfoInputCountShift) |
            componentTypeOrBits;

        for(PolymorphicComponent output : movedTriggers)
        {
          assert(outputs.contains(output));
          componentConnectivityTable[componentOffset++] = ((ForwardDeadReckonComponent)output).id;
        }

        //  Original component now outputs to the remainign subset of its original
        //  outputs plus the dummy OR
        numOutputs -= (movedTriggers.size()-1);
      }

      componentInfo[id] = ((long)componentOffset << componentInfoConnectivityOffsetShift) |
                               ((long)numOutputs << componentInfoOutputCountShift) |
                               ((long)inputs.size() << componentInfoInputCountShift) |
                               ((long)componentType << componentInfoTypeShift) |
                               (outputInverted ? componentInfoOutputInverted : 0);

      for(PolymorphicComponent output : outputs)
      {
        if ( !outTypeClash ||
             (output instanceof PolymorphicOr) ||
             (output instanceof PolymorphicAnd) ||
             (output instanceof PolymorphicNot) ||
             (output instanceof PolymorphicProposition && ((ForwardDeadReckonProposition)output).getAssociatedMoveIndex() == -1))
        {
          componentConnectivityTable[componentOffset++] = ((ForwardDeadReckonComponent)output).id;
        }
      }
      if ( outTypeClash )
      {
        //  Add the dumm OR to the original's output list
        componentConnectivityTable[componentOffset++] = (id-1) | (componentTypeUniversalLogic << 24);
      }
    }
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
      instances[i] = new InstanceInfo(componentInfo.length);
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

    for(int i = 0; i < componentInfo.length; i++)
    {
      //  Retrieve the actual component type - note that we MUST retrieve this from
      //  the component info NOT its id, since the id representation uses the same
      //  code for AND and OR and encodes NAND and NOR as well
      long compInfo = componentInfo[i];
      int componentTypeBits = (int)(compInfo & componentInfoTypeMask);
      boolean outputInverted = ((compInfo & componentInfoOutputInverted) != 0);

      switch(componentTypeBits)
      {
        case componentTypePropositionBits:
        case componentTypeTransitionBits:
        case componentTypeFalseConstantBits:
          state[i] = 0;
          break;
        case componentTypeNonTriggeringPropositionBits:
        case componentTypeOrBits:
          if ( outputInverted )
          {
            state[i] = 0xFFFFFFFF;
          }
          else
          {
            state[i] = 0x7FFFFFFF;
          }
          break;
        case componentTypeAndBits:
          int   numInputs = (int)((compInfo & componentInfoInputCountMask) >> componentInfoInputCountShift);
          if ( outputInverted )
          {
            state[i] = -numInputs;
          }
          else
          {
            state[i] = 0x80000000 - numInputs;
          }
          break;
        case componentTypeNotBits:
          state[i] = 0xFFFFFFFF;
          break;
        case componentTypeTrueConstantBits:
          state[i] = componentStateCachedValMask;
          break;
        default:
          break;
      }
    }

    if ( fullPropagate )
    {
      for(int i = 0; i < componentInfo.length; i++)
      {
        long  compInfo = componentInfo[i];
        int   numOutputs = (int)((compInfo & componentInfoOutputCountMask) >> componentInfoOutputCountShift);

        instanceInfo.resetWatermark = i;

        if ( numOutputs > 0 )
        {
          boolean current = ((state[i] & componentStateCachedValMask) != 0);
          int type = (int)((compInfo & componentInfoTypeMask) >> componentInfoTypeShift);

          if ( current && type != componentTypeTransition )
          {
            propagateComponent(instanceInfo, i, current);
          }
        }
      }
    }
    else
    {
      instanceInfo.resetWatermark = componentInfo.length;
    }
  }

  private void propagateComponentTrue(InstanceInfo instanceInfo, int componentIdFull)
  {
    int[] state = instanceInfo.state;
    long  compInfo = componentInfo[componentIdFull & 0xFFFFFF];
    int   numOutputs = (int)((compInfo & componentInfoOutputCountMask) >> componentInfoOutputCountShift);
    int   outputIndex = (int)((compInfo & componentInfoConnectivityOffsetMask) >> componentInfoConnectivityOffsetShift);

    if ((componentIdFull & componentIdOutputUniversalLogicBits) == 0)
    {
      while(numOutputs-- > 0)
      {
        int outputFullId = componentConnectivityTable[outputIndex+numOutputs];
        int outputId = outputFullId & 0xFFFFFF;

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
        int outputFullId = componentConnectivityTable[outputIndex+numOutputs];

        int stateVal = ++state[outputFullId & 0xFFFFFF];
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
    long  compInfo = componentInfo[componentIdFull & 0xFFFFFF];
    int   numOutputs = (int)((compInfo & componentInfoOutputCountMask) >> componentInfoOutputCountShift);
    int   outputIndex = (int)((compInfo & componentInfoConnectivityOffsetMask) >> componentInfoConnectivityOffsetShift);

    if ((componentIdFull & componentIdOutputUniversalLogicBits) == 0)
    {
      while(numOutputs-- > 0)
      {
        int outputFullId = componentConnectivityTable[outputIndex+numOutputs];
        int outputId = outputFullId & 0xFFFFFF;

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
        int outputFullId = componentConnectivityTable[outputIndex+numOutputs];

        int stateVal = --state[outputFullId & 0xFFFFFF];
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
    if ( instanceInfo.resetWatermark >= componentId )
    {
      int[] state = instanceInfo.state;
      long  compInfo = componentInfo[componentId];
      int   numOutputs = (int)((compInfo & componentInfoOutputCountMask) >> componentInfoOutputCountShift);
      int   outputIndex = (int)((compInfo & componentInfoConnectivityOffsetMask) >> componentInfoConnectivityOffsetShift);
      int outputIdBits;

      while(numOutputs-- > 0)
      {
        int outputFullId = componentConnectivityTable[outputIndex+numOutputs];
        int outputId = outputFullId & 0xFFFFFF;
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

            propagateComponent(instanceInfo, outputId, value);
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
                propagateComponent(instanceInfo, outputId, true);
              }
              else if ( stateVal == 0 )
              {
                propagateComponent(instanceInfo, outputId, false);
              }
            }
            else
            {
              stateVal = --state[outputId];
              if ( stateVal == 0x7FFFFFFF )
              {
                propagateComponent(instanceInfo, outputId, false);
              }
              else if ( stateVal == 0xFFFFFFFF )
              {
                propagateComponent(instanceInfo, outputId, true);
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
    int compId = propId & 0xFFFFFF;

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
    int compId = propId & 0xFFFFFF;
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
    return ((state[propId & 0xFFFFFF] & componentStateCachedValMask) != 0);
  }
}
