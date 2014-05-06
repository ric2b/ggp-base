package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.Collection;

import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;
import org.ggp.base.util.propnet.polymorphic.PolymorphicNot;
import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;

public class ForwardDeadReckonPropnetFastAnimator
{
  public class InstanceInfo
  {
    public InstanceInfo()
    {
      // TODO Auto-generated constructor stub
    }
    int[]                                         state;
    ForwardDeadReckonComponentTransitionNotifier  legalMoveNotifier;
    ForwardDeadReckonComponentTransitionNotifier  propositionTransitionNotifier;
    int                                           resetWatermark;
  }

  private ForwardDeadReckonPropNet propNet;
  private long[]                   componentInfo;
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

  private int[]                    componentAssociatedTransitionIndexes;
  private int[]                    componentConnectivityTable;
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
  private final int                componentIdNonTriggeringPropositionBits = (componentTypeNonTriggeringProposition << componentIdTypeShift);
  private final int                componentIdPropositionBits = (componentTypeProposition << componentIdTypeShift);
  private final int                componentIdTransitionBits = (componentTypeTransition << componentIdTypeShift);
  private final int                componentIdUniversalLogicBits = (componentTypeUniversalLogic << componentIdTypeShift);
  private final int                componentIdOutputUniversalLogicBits = (1 << 28);
  private final int                componentIdOutputInvertedFlag = (1<<29);

  // states will be represented by per-thread int arrays, with each int packed as follows
  private final int                componentStateCachedValMask = 1<<31; // Current state
  //  Remaining bits are free for opaque use by the implementation

  private InstanceInfo[]           instances;

  public ForwardDeadReckonPropnetFastAnimator(ForwardDeadReckonPropNet propNet)
  {
    this.propNet = propNet;

    int index = 0;
    int outputCount = 0;
    int componentType = 0;

    //  Number components
    for(PolymorphicComponent c : propNet.getComponents())
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
          componentType = componentTypeNonTriggeringProposition;
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

      for(PolymorphicComponent output : outputs)
      {
        if ( !outTypeSet )
        {
          outTypeSet = true;
          if ( (output instanceof PolymorphicOr) || (output instanceof PolymorphicAnd) )
          {
            outputTypeBits = componentIdOutputUniversalLogicBits;
          }
          else if ( output instanceof PolymorphicNot )
          {
            outputTypeBits = componentIdOutputUniversalLogicBits;
           }
          else
          {
            break;
          }
        }
        else
        {
          if ( (output instanceof PolymorphicOr) || (output instanceof PolymorphicAnd) )
          {
            if ( outputTypeBits != componentIdOutputUniversalLogicBits)
            {
              outputTypeBits = 0;
              break;
            }
          }
          else if ( output instanceof PolymorphicNot )
          {
            if ( outputTypeBits != componentIdOutputUniversalLogicBits)
            {
              outputTypeBits = 0;
              break;
            }
          }
          else
          {
            outputTypeBits = 0;
            break;
          }
        }
      }

      fdrc.id = index++ |
                (componentType << 24) |
                outputTypeBits |
                (outputsInverted ? componentIdOutputInvertedFlag : 0);
    }

    componentInfo = new long[index];
    componentAssociatedTransitionIndexes = new int[index];
    componentConnectivityTable = new int[outputCount];
    int componentOffset = 0;

    for(PolymorphicComponent c : propNet.getComponents())
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
        componentType = componentTypeProposition;
        componentAssociatedTransitionIndexes[id] = ((ForwardDeadReckonProposition)c).getAssociatedMoveIndex();
      }
      else if ( c instanceof PolymorphicTransition )
      {
        componentType = componentTypeTransition;
        componentAssociatedTransitionIndexes[id] = ((ForwardDeadReckonTransition)c).getAssociatedPropositionIndex();
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

      componentInfo[id] = ((long)componentOffset << componentInfoConnectivityOffsetShift) |
                               ((long)outputs.size() << componentInfoOutputCountShift) |
                               ((long)inputs.size() << componentInfoInputCountShift) |
                               ((long)componentType << componentInfoTypeShift) |
                               (outputInverted ? componentInfoOutputInverted : 0);

      for(PolymorphicComponent output : outputs)
      {
        componentConnectivityTable[componentOffset++] = ((ForwardDeadReckonComponent)output).id;
      }
    }
  }

  public void crystalize(int numInstances)
  {
    instances = new InstanceInfo[numInstances];

    for(int i = 0; i < numInstances; i++)
    {
      instances[i] = new InstanceInfo();
      instances[i].state = new int[propNet.getComponents().size()];
      instances[i].legalMoveNotifier = propNet.getActiveLegalProps(i);
      instances[i].propositionTransitionNotifier = propNet.getActiveBaseProps(i);
    }
  }

  public InstanceInfo getInstanceInfo(int instanceId)
  {
    return instances[instanceId];
  }

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
        case componentTypeNonTriggeringPropositionBits:
          state[i] = 0;
          break;
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
        //boolean lastPropagated = ((state[i] & componentInfoLastPropagatedMask) != 0);
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
        int outputIdBits = outputFullId & componentIdTypeMask;

        switch(outputIdBits)
        {
          case componentIdNonTriggeringPropositionBits:
            state[outputId] |= componentStateCachedValMask;
            break;
          case componentIdPropositionBits:
            state[outputId] |= componentStateCachedValMask;

            int moveIndex = componentAssociatedTransitionIndexes[outputId];
            instanceInfo.legalMoveNotifier.add(moveIndex);
            break;
          case componentIdTransitionBits:
            state[outputId] |= componentStateCachedValMask;

            int propIndex = componentAssociatedTransitionIndexes[outputId];
            instanceInfo.propositionTransitionNotifier.add(propIndex);
            break;
          case componentIdUniversalLogicBits:
            int stateVal = ++state[outputId];
            if (stateVal == 0x80000000)
            {
              propagateComponentTrue(instanceInfo, outputFullId);
            }
            else if ( stateVal == 0 )
            {
              propagateComponentFalse(instanceInfo, outputFullId);
            }
            break;
          default:
            //  Should not happen
            throw new UnsupportedOperationException("Unexpected component type");
        }
      }
    }
    else
    {
      while(numOutputs-- > 0)
      {
        int outputFullId = componentConnectivityTable[outputIndex+numOutputs];

        int stateVal = ++state[outputFullId & 0xFFFFFF];
        if (stateVal == 0x80000000)
        {
          propagateComponentTrue(instanceInfo, outputFullId);
        }
        else if ( stateVal == 0 )
        {
          propagateComponentFalse(instanceInfo, outputFullId);
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
        int outputIdBits = outputFullId & componentIdTypeMask;

        switch(outputIdBits)
        {
          case componentIdNonTriggeringPropositionBits:
            state[outputId] &= ~componentStateCachedValMask;
            break;
          case componentIdPropositionBits:
            state[outputId] &= ~componentStateCachedValMask;

            int moveIndex = componentAssociatedTransitionIndexes[outputId];
            instanceInfo.legalMoveNotifier.remove(moveIndex);
            break;
          case componentIdTransitionBits:
            state[outputId] &= ~componentStateCachedValMask;

            int propIndex = componentAssociatedTransitionIndexes[outputId];
            instanceInfo.propositionTransitionNotifier.remove(propIndex);
            break;
          case componentIdUniversalLogicBits:
            int stateVal = --state[outputId];
            if ( stateVal == 0x7FFFFFFF )
            {
              propagateComponentFalse(instanceInfo, outputFullId);
            }
            else if ( stateVal == 0xFFFFFFFF )
            {
              propagateComponentTrue(instanceInfo, outputFullId);
            }
            break;
          default:
            //  Should not happen
            throw new UnsupportedOperationException("Unexpected component type");
        }
      }
    }
    else
    {
      while(numOutputs-- > 0)
      {
        int outputFullId = componentConnectivityTable[outputIndex+numOutputs];

        int stateVal = --state[outputFullId & 0xFFFFFF];
        if ( stateVal == 0x7FFFFFFF )
        {
          propagateComponentFalse(instanceInfo, outputFullId);
        }
        else if ( stateVal == 0xFFFFFFFF )
        {
          propagateComponentTrue(instanceInfo, outputFullId);
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
          case componentIdNonTriggeringPropositionBits:
            if ( value )
            {
              state[outputId] |= componentStateCachedValMask;
            }
            else
            {
              state[outputId] &= ~componentStateCachedValMask;
            }
            propagateComponent(instanceInfo, outputId, value);
            break;
          case componentIdPropositionBits:
            if ( value )
            {
              state[outputId] |= componentStateCachedValMask;
            }
            else
            {
              state[outputId] &= ~componentStateCachedValMask;
            }

            int moveIndex = componentAssociatedTransitionIndexes[outputId];

            if ( moveIndex != -1 )
            {
              if ( value )
              {
                instanceInfo.legalMoveNotifier.add(moveIndex);
              }
              else
              {
                instanceInfo.legalMoveNotifier.remove(moveIndex);
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

            int propIndex = componentAssociatedTransitionIndexes[outputId];

            if ( value )
            {
              instanceInfo.propositionTransitionNotifier.add(propIndex);
            }
            else
            {
              instanceInfo.propositionTransitionNotifier.remove(propIndex);
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
   * Chnage value of an input proposition, asserting that the new value IS a change
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

  public boolean getComponentValue(int instanceId, int propId)
  {
    int[] state = instances[instanceId].state;
    return ((state[propId & 0xFFFFFF] & componentStateCachedValMask) != 0);
  }
}
