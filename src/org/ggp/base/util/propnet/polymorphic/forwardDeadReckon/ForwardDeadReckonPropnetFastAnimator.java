package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.List;

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
  private final int                componentIdOrBits = (componentTypeOr << componentIdTypeShift);
  private final int                componentIdAndBits = (componentTypeAnd << componentIdTypeShift);
  private final int                componentIdNotBits = (componentTypeNot << componentIdTypeShift);

  // states will be represented by per-thread int arrays, with each int packed as follows
  private final int                componentInfoOpaqueValueMask = 0x3FFFFFFF; // value mask
  private final int                componentInfoCachedValMask = 1<<31; // Current state

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
      boolean hasPropagatableOutputs = !fdrc.getOutputs().isEmpty();
      boolean hasTrigger = false;

      outputCount += fdrc.getOutputs().size();

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

      fdrc.id = index++ |
                (componentType << 24);
    }

    componentInfo = new long[index];
    componentAssociatedTransitionIndexes = new int[index];
    componentConnectivityTable = new int[outputCount];
    int componentOffset = 0;

    for(PolymorphicComponent c : propNet.getComponents())
    {
      ForwardDeadReckonComponent fdrc = (ForwardDeadReckonComponent)c;
      int id = fdrc.id & 0xFFFFFF;

      @SuppressWarnings("unchecked")
      List<PolymorphicComponent> outputs = (List<PolymorphicComponent>)c.getOutputs();
      @SuppressWarnings("unchecked")
      List<PolymorphicComponent> inputs = (List<PolymorphicComponent>)c.getInputs();
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
      componentInfo[id] = ((long)componentOffset << componentInfoConnectivityOffsetShift) |
                               ((long)outputs.size() << componentInfoOutputCountShift) |
                               ((long)inputs.size() << componentInfoInputCountShift) |
                               ((long)componentType << componentInfoTypeShift);

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
      long compInfo = componentInfo[i];
      int componentTypeBits = (int)(compInfo & componentInfoTypeMask);

      switch(componentTypeBits)
      {
        case componentTypePropositionBits:
        case componentTypeTransitionBits:
        case componentTypeOrBits:
        case componentTypeFalseConstantBits:
        case componentTypeNonTriggeringPropositionBits:
          state[i] = 0;
          break;
        case componentTypeAndBits:
          int   numInputs = (int)((compInfo & componentInfoInputCountMask) >> componentInfoInputCountShift);
          state[i] = numInputs;
          break;
        case componentTypeNotBits:
          state[i] = componentInfoCachedValMask;
          break;
        case componentTypeTrueConstantBits:
          state[i] = componentInfoCachedValMask;
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
          boolean current = ((state[i] & componentInfoCachedValMask) != 0);
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

  private void propagateComponentTrue(InstanceInfo instanceInfo, int componentId)
  {
    int[] state = instanceInfo.state;
    long  compInfo = componentInfo[componentId];
    int   numOutputs = (int)((compInfo & componentInfoOutputCountMask) >> componentInfoOutputCountShift);
    int   outputIndex = (int)((compInfo & componentInfoConnectivityOffsetMask) >> componentInfoConnectivityOffsetShift);
    int stateVal;

    while(numOutputs-- > 0)
    {
      int outputFullId = componentConnectivityTable[outputIndex+numOutputs];
      int outputId = outputFullId & 0xFFFFFF;
      int outputIdBits = outputFullId & componentIdTypeMask;

      switch(outputIdBits)
      {
        case componentIdNonTriggeringPropositionBits:
          state[outputId] |= componentInfoCachedValMask;
          break;
        case componentIdPropositionBits:
          state[outputId] |= componentInfoCachedValMask;

          int moveIndex = componentAssociatedTransitionIndexes[outputId];
          instanceInfo.legalMoveNotifier.add(moveIndex);
          break;
        case componentIdTransitionBits:
          state[outputId] |= componentInfoCachedValMask;

          int propIndex = componentAssociatedTransitionIndexes[outputId];
          instanceInfo.propositionTransitionNotifier.add(propIndex);
          break;
        case componentIdOrBits:
          stateVal = state[outputId] + 1;

          if ((stateVal & componentInfoOpaqueValueMask) != 1)
          {
            state[outputId] = stateVal;
          }
          else
          {
            state[outputId] = stateVal | componentInfoCachedValMask;
            propagateComponentTrue(instanceInfo, outputId);
          }
          break;
        case componentIdAndBits:
          stateVal = state[outputId] - 1;

          if ((stateVal & componentInfoOpaqueValueMask) != 0)
          {
            state[outputId] = stateVal;
          }
          else
          {
            state[outputId] = stateVal | componentInfoCachedValMask;
            propagateComponentTrue(instanceInfo, outputId);
          }
          break;
        case componentIdNotBits:
          state[outputId] &= ~componentInfoCachedValMask;
          propagateComponentFalse(instanceInfo, outputId);
          break;
        default:
          //  Should not happen
          throw new UnsupportedOperationException("Unexpected component type");
      }
    }
  }

  private void propagateComponentFalse(InstanceInfo instanceInfo, int componentId)
  {
    int[] state = instanceInfo.state;
    long  compInfo = componentInfo[componentId];
    int   numOutputs = (int)((compInfo & componentInfoOutputCountMask) >> componentInfoOutputCountShift);
    int   outputIndex = (int)((compInfo & componentInfoConnectivityOffsetMask) >> componentInfoConnectivityOffsetShift);
    int stateVal;

    while(numOutputs-- > 0)
    {
      int outputFullId = componentConnectivityTable[outputIndex+numOutputs];
      int outputId = outputFullId & 0xFFFFFF;
      int outputIdBits = outputFullId & componentIdTypeMask;

      switch(outputIdBits)
      {
        case componentIdNonTriggeringPropositionBits:
          state[outputId] &= ~componentInfoCachedValMask;
          break;
        case componentIdPropositionBits:
          state[outputId] &= ~componentInfoCachedValMask;

          int moveIndex = componentAssociatedTransitionIndexes[outputId];
          instanceInfo.legalMoveNotifier.remove(moveIndex);
          break;
        case componentIdTransitionBits:
          state[outputId] &= ~componentInfoCachedValMask;

          int propIndex = componentAssociatedTransitionIndexes[outputId];
          instanceInfo.propositionTransitionNotifier.remove(propIndex);
          break;
        case componentIdOrBits:
          stateVal = state[outputId] - 1;

          if ((stateVal & componentInfoOpaqueValueMask) != 0)
          {
            state[outputId] = stateVal;
          }
          else
          {
            state[outputId] = stateVal & ~componentInfoCachedValMask;
            propagateComponentFalse(instanceInfo, outputId);
          }
          break;
        case componentIdAndBits:
          stateVal = state[outputId] + 1;

          if ((stateVal & componentInfoOpaqueValueMask) != 1)
          {
            state[outputId] = stateVal;
          }
          else
          {
            state[outputId] = stateVal & ~componentInfoCachedValMask;
            propagateComponentFalse(instanceInfo, outputId);
          }
          break;
        case componentIdNotBits:
          state[outputId] |= componentInfoCachedValMask;
          propagateComponentTrue(instanceInfo, outputId);
          break;
        default:
          //  Should not happen
          throw new UnsupportedOperationException("Unexpected component type");
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
      int stateVal;

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
              state[outputId] |= componentInfoCachedValMask;
            }
            else
            {
              state[outputId] &= ~componentInfoCachedValMask;
            }
            propagateComponent(instanceInfo, outputId, value);
            break;
          case componentIdPropositionBits:
            if ( value )
            {
              state[outputId] |= componentInfoCachedValMask;
            }
            else
            {
              state[outputId] &= ~componentInfoCachedValMask;
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
              state[outputId] |= componentInfoCachedValMask;
            }
            else
            {
              state[outputId] &= ~componentInfoCachedValMask;
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
          case componentIdOrBits:
            if ( value )
            {
              stateVal = state[outputId] + 1;
            }
            else
            {
              stateVal = state[outputId] - 1;
            }

            if ((stateVal & componentInfoOpaqueValueMask) == (value ? 1 : 0))
            {
              if ( value )
              {
                state[outputId] = stateVal | componentInfoCachedValMask;
              }
              else
              {
                state[outputId] = stateVal & ~componentInfoCachedValMask;
              }
              propagateComponent(instanceInfo, outputId, value);
            }
            else
            {
              state[outputId] = stateVal;
            }
            break;
          case componentIdAndBits:
            if ( value )
            {
              stateVal = state[outputId] - 1;
            }
            else
            {
              stateVal = state[outputId] + 1;
            }

            if ((stateVal & componentInfoOpaqueValueMask) == (value ? 0 : 1))
            {
              if ( value )
              {
                state[outputId] = stateVal | componentInfoCachedValMask;
              }
              else
              {
                state[outputId] = stateVal & ~componentInfoCachedValMask;
              }
              propagateComponent(instanceInfo, outputId, value);
            }
            else
            {
              state[outputId] = stateVal;
            }
            break;
          case componentIdNotBits:
            if ( value )
            {
              state[outputId] &= ~componentInfoCachedValMask;
            }
            else
            {
              state[outputId] |= componentInfoCachedValMask;
            }
            propagateComponent(instanceInfo, outputId, !value);
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
      state[compId] |= componentInfoCachedValMask;
      propagateComponentTrue(instanceInfo, compId);
    }
    else
    {
      state[compId] &= ~componentInfoCachedValMask;
      propagateComponentFalse(instanceInfo, compId);
    }
  }

  public void setComponentValue(int instanceId, int propId, boolean value)
  {
    InstanceInfo instanceInfo = instances[instanceId];
    int[] state = instanceInfo.state;
    int compId = propId & 0xFFFFFF;
    boolean currentValue = ((state[compId] & componentInfoCachedValMask) != 0);

    if ( value != currentValue )
    {
      if ( value )
      {
        state[compId] |= componentInfoCachedValMask;
        propagateComponentTrue(instanceInfo, compId);
      }
      else
      {
        state[compId] &= ~componentInfoCachedValMask;
        propagateComponentFalse(instanceInfo, compId);
      }
    }
  }

  public boolean getComponentValue(int instanceId, int propId)
  {
    int[] state = instances[instanceId].state;
    return ((state[propId & 0xFFFFFF] & componentInfoCachedValMask) != 0);
  }
}
