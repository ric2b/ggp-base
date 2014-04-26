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
  private class InstanceInfo
  {
    int[]                                         state;
    ForwardDeadReckonComponentTransitionNotifier  legalMoveNotifier;
    ForwardDeadReckonComponentTransitionNotifier  propositionTransitionNotifier;
    int                                           resetWatermark;
  }

  private ForwardDeadReckonPropNet propNet;
  private long[]                   componentInfo;
  //  each long is a packed record using the following masks:
  private final long               componentInfoTypeMask = 0x07L; //  3-bits for component type
  private final int                componentInfoTypeShift = 0;
  private final long               componentInfoOutputCountMask = 0xFFFF00000000L; // Num outputs mask (16 bits)
  private final int                componentInfoOutputCountShift = 32;
  private final long               componentInfoConnectivityOffsetMask = 0xFFFFFF00L; // Start offset of output indexes in connectivity array
  private final int                componentInfoConnectivityOffsetShift = 8;
  private final long               componentInfoInputCountMask = 0xFFFF000000000000L; // Num inputs mask (16 bits)
  private final int                componentInfoInputCountShift = 48;
  private final long               componentInfoHasTriggerMask = 0x8L; // Whether this component has a transition trigger associated with it
  private final long               componentOutputMonocultureMask = 0x10L; // Whether this component has outputs all of the same type

  private int[]                    componentAssociatedTransitionIndexes;
  private int[]                    componentConnectivityTable;
  //  Component types
  private final int                componentTypeProposition = 0;
  private final int                componentTypeTransition = 1;
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

  // states will be represented by per-thread int arrays, with each int packed as follows
  private final int                componentInfoOpaqueValueMask = 0x3FFFFFFF; // value mask
  private final int                componentInfoCachedValMask = 1<<31; // Current state
  private final int                componentInfoLastPropagatedMask = 1<<30; // Last propagated state

  private InstanceInfo[]           instances;

  public ForwardDeadReckonPropnetFastAnimator(ForwardDeadReckonPropNet propNet)
  {
    this.propNet = propNet;

    int index = 0;
    int outputCount = 0;

    for(PolymorphicComponent c : propNet.getComponents())
    {
      ForwardDeadReckonComponent fdrc = (ForwardDeadReckonComponent)c;

      outputCount += fdrc.getOutputs().size();
      fdrc.id = index++;
    }

    componentInfo = new long[index];
    componentAssociatedTransitionIndexes = new int[index];
    componentConnectivityTable = new int[outputCount];
    int componentOffset = 0;

    for(PolymorphicComponent c : propNet.getComponents())
    {
      ForwardDeadReckonComponent fdrc = (ForwardDeadReckonComponent)c;
      @SuppressWarnings("unchecked")
      List<PolymorphicComponent> outputs = (List<PolymorphicComponent>)c.getOutputs();
      @SuppressWarnings("unchecked")
      List<PolymorphicComponent> inputs = (List<PolymorphicComponent>)c.getInputs();
      int componentType = 0;
      boolean hasTransitionTrigger = false;

      if ( c instanceof PolymorphicProposition )
      {
        componentType = componentTypeProposition;
        componentAssociatedTransitionIndexes[fdrc.id] = ((ForwardDeadReckonProposition)c).getAssociatedMoveIndex();
        hasTransitionTrigger = (componentAssociatedTransitionIndexes[fdrc.id] != -1);
      }
      else if ( c instanceof PolymorphicTransition )
      {
        componentType = componentTypeTransition;
        componentAssociatedTransitionIndexes[fdrc.id] = ((ForwardDeadReckonTransition)c).getAssociatedPropositionIndex();
        hasTransitionTrigger = (componentAssociatedTransitionIndexes[fdrc.id] != -1);
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
      componentInfo[fdrc.id] = ((long)componentOffset << componentInfoConnectivityOffsetShift) |
                               ((long)outputs.size() << componentInfoOutputCountShift) |
                               ((long)inputs.size() << componentInfoInputCountShift) |
                               ((long)componentType << componentInfoTypeShift) |
                               (hasTransitionTrigger ? componentInfoHasTriggerMask : 0);

      boolean outputsAllSameType = (outputs.size() > 2);
      Class<? extends PolymorphicComponent>   lastSeenOutputClass = null;

      for(PolymorphicComponent output : outputs)
      {
        componentConnectivityTable[componentOffset++] = ((ForwardDeadReckonComponent)output).id;

        if ( output instanceof PolymorphicProposition ||
             output instanceof PolymorphicTransition )
        {
          outputsAllSameType = false;
        }
        else
        {
          Class<? extends PolymorphicComponent> outputClass = output.getClass();
          if ( lastSeenOutputClass == null )
          {
            lastSeenOutputClass = outputClass;
          }
          else if ( lastSeenOutputClass != outputClass )
          {
            outputsAllSameType = false;
          }
        }
      }

      if ( outputsAllSameType )
      {
        componentInfo[fdrc.id] |= componentOutputMonocultureMask;
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
    boolean allOutputsSameType = ((compInfo & componentOutputMonocultureMask) != 0);
    int outputTypeBits;
    long outputInfo;
    int stateVal;

    if ( allOutputsSameType )
    {
      int outputId = componentConnectivityTable[outputIndex++];
      outputInfo = componentInfo[outputId];
      outputTypeBits = (int)(outputInfo & componentInfoTypeMask);

      switch(outputTypeBits)
      {
        case componentTypeOrBits:
          stateVal = state[outputId] + 1;

          if ((stateVal & componentInfoOpaqueValueMask) == 1)
          {
            state[outputId] = stateVal | componentInfoCachedValMask;
            propagateComponentTrue(instanceInfo, outputId);
          }
          else
          {
            state[outputId] = stateVal;
          }

          while(--numOutputs > 0)
          {
            outputId = componentConnectivityTable[outputIndex++];
            stateVal = state[outputId] + 1;

            if ((stateVal & componentInfoOpaqueValueMask) == 1)
            {
              state[outputId] = stateVal | componentInfoCachedValMask;
              propagateComponentTrue(instanceInfo, outputId);
            }
            else
            {
              state[outputId] = stateVal;
            }
          }
          break;
        case componentTypeAndBits:
          stateVal = state[outputId] - 1;

          if ((stateVal & componentInfoOpaqueValueMask) == 0)
          {
            state[outputId] = stateVal | componentInfoCachedValMask;
            propagateComponentTrue(instanceInfo, outputId);
          }
          else
          {
            state[outputId] = stateVal;
          }
          while(--numOutputs > 0)
          {
            outputId = componentConnectivityTable[outputIndex++];
            stateVal = state[outputId] - 1;

            if ((stateVal & componentInfoOpaqueValueMask) == 0)
            {
              state[outputId] = stateVal | componentInfoCachedValMask;
              propagateComponentTrue(instanceInfo, outputId);
            }
            else
            {
              state[outputId] = stateVal;
            }
          }
          break;
        case componentTypeNotBits:
          outputId = componentConnectivityTable[outputIndex++];

          state[outputId] &= ~componentInfoCachedValMask;
          propagateComponentFalse(instanceInfo, outputId);

          while(--numOutputs > 0)
          {
            outputId = componentConnectivityTable[outputIndex++];

            state[outputId] &= ~componentInfoCachedValMask;
            propagateComponentFalse(instanceInfo, outputId);
          }
          break;
        default:
          //  Should not happen
          throw new UnsupportedOperationException("Unexpected component type");
      }
    }
    else
    {
      while(numOutputs-- > 0)
      {
        int outputId = componentConnectivityTable[outputIndex++];
        outputInfo = componentInfo[outputId];
        outputTypeBits = (int)(outputInfo & componentInfoTypeMask);

        switch(outputTypeBits)
        {
          case componentTypePropositionBits:
            state[outputId] |= componentInfoCachedValMask;

            if ( (outputInfo & componentInfoHasTriggerMask) != 0 )
            {
              int moveIndex = componentAssociatedTransitionIndexes[outputId];

              instanceInfo.legalMoveNotifier.add(moveIndex);
            }

            if ( (outputInfo & componentInfoOutputCountMask) != 0 )
            {
              propagateComponentTrue(instanceInfo, outputId);
            }
            break;
          case componentTypeTransitionBits:
            state[outputId] |= componentInfoCachedValMask;

            if ( (outputInfo & componentInfoHasTriggerMask) != 0 )
            {
              int propIndex = componentAssociatedTransitionIndexes[outputId];

              instanceInfo.propositionTransitionNotifier.add(propIndex);
            }
            break;
          case componentTypeOrBits:
            stateVal = state[outputId] + 1;

            if ((stateVal & componentInfoOpaqueValueMask) == 1)
            {
              state[outputId] = stateVal | componentInfoCachedValMask;
              propagateComponentTrue(instanceInfo, outputId);
            }
            else
            {
              state[outputId] = stateVal;
            }
            break;
          case componentTypeAndBits:
            stateVal = state[outputId] - 1;

            if ((stateVal & componentInfoOpaqueValueMask) == 0)
            {
              state[outputId] = stateVal | componentInfoCachedValMask;
              propagateComponentTrue(instanceInfo, outputId);
            }
            else
            {
              state[outputId] = stateVal;
            }
            break;
          case componentTypeNotBits:
            state[outputId] &= ~componentInfoCachedValMask;
            propagateComponentFalse(instanceInfo, outputId);
            break;
          default:
            //  Should not happen
            throw new UnsupportedOperationException("Unexpected component type");
        }
      }
    }
  }

  private void propagateComponentFalse(InstanceInfo instanceInfo, int componentId)
  {
    int[] state = instanceInfo.state;
    long  compInfo = componentInfo[componentId];
    int   numOutputs = (int)((compInfo & componentInfoOutputCountMask) >> componentInfoOutputCountShift);
    int   outputIndex = (int)((compInfo & componentInfoConnectivityOffsetMask) >> componentInfoConnectivityOffsetShift);
    boolean allOutputsSameType = ((compInfo & componentOutputMonocultureMask) != 0);
    int outputTypeBits;
    long outputInfo;
    int stateVal;

    if ( allOutputsSameType )
    {
      int outputId = componentConnectivityTable[outputIndex++];
      outputInfo = componentInfo[outputId];
      outputTypeBits = (int)(outputInfo & componentInfoTypeMask);

      switch(outputTypeBits)
      {
        case componentTypeOrBits:
          stateVal = state[outputId] - 1;

          if ((stateVal & componentInfoOpaqueValueMask) == 0)
          {
            state[outputId] = stateVal & ~componentInfoCachedValMask;
            propagateComponentFalse(instanceInfo, outputId);
          }
          else
          {
            state[outputId] = stateVal;
          }
          while(--numOutputs > 0)
          {
            outputId = componentConnectivityTable[outputIndex++];
            stateVal = state[outputId] - 1;

            if ((stateVal & componentInfoOpaqueValueMask) == 0)
            {
              state[outputId] = stateVal & ~componentInfoCachedValMask;
              propagateComponentFalse(instanceInfo, outputId);
            }
            else
            {
              state[outputId] = stateVal;
            }
          }
          break;
        case componentTypeAndBits:
          stateVal = state[outputId] + 1;

          if ((stateVal & componentInfoOpaqueValueMask) == 1)
          {
            state[outputId] = stateVal & ~componentInfoCachedValMask;
            propagateComponentFalse(instanceInfo, outputId);
          }
          else
          {
            state[outputId] = stateVal;
          }
          while(--numOutputs > 0)
          {
            outputId = componentConnectivityTable[outputIndex++];
            stateVal = state[outputId] + 1;

            if ((stateVal & componentInfoOpaqueValueMask) == 1)
            {
              state[outputId] = stateVal & ~componentInfoCachedValMask;
              propagateComponentFalse(instanceInfo, outputId);
            }
            else
            {
              state[outputId] = stateVal;
            }
          }
          break;
        case componentTypeNotBits:
          state[outputId] |= componentInfoCachedValMask;
          propagateComponentTrue(instanceInfo, outputId);
          while(--numOutputs > 0)
          {
            outputId = componentConnectivityTable[outputIndex++];

            state[outputId] |= componentInfoCachedValMask;
            propagateComponentTrue(instanceInfo, outputId);
          }
          break;
        default:
          //  Should not happen
          throw new UnsupportedOperationException("Unexpected component type");
      }
    }
    else
    {
      while(numOutputs-- > 0)
      {
        int outputId = componentConnectivityTable[outputIndex++];
        outputInfo = componentInfo[outputId];
        outputTypeBits = (int)(outputInfo & componentInfoTypeMask);

        switch(outputTypeBits)
        {
          case componentTypePropositionBits:
            state[outputId] &= ~componentInfoCachedValMask;

            if ( (outputInfo & componentInfoHasTriggerMask) != 0 )
            {
              int moveIndex = componentAssociatedTransitionIndexes[outputId];
              //assert ( moveIndex != -1 );

              instanceInfo.legalMoveNotifier.remove(moveIndex);
            }
            if ( (outputInfo & componentInfoOutputCountMask) != 0 )
            {
              propagateComponentFalse(instanceInfo, outputId);
            }
            break;
          case componentTypeTransitionBits:
            state[outputId] &= ~componentInfoCachedValMask;

            if ( (outputInfo & componentInfoHasTriggerMask) != 0 )
            {
              int propIndex = componentAssociatedTransitionIndexes[outputId];
              //assert ( propIndex != -1 );
              instanceInfo.propositionTransitionNotifier.remove(propIndex);
            }
            break;
          case componentTypeOrBits:
            stateVal = state[outputId] - 1;

            if ((stateVal & componentInfoOpaqueValueMask) == 0)
            {
              state[outputId] = stateVal & ~componentInfoCachedValMask;
              propagateComponentFalse(instanceInfo, outputId);
            }
            else
            {
              state[outputId] = stateVal;
            }
            break;
          case componentTypeAndBits:
            stateVal = state[outputId] + 1;

            if ((stateVal & componentInfoOpaqueValueMask) == 1)
            {
              state[outputId] = stateVal & ~componentInfoCachedValMask;
              propagateComponentFalse(instanceInfo, outputId);
            }
            else
            {
              state[outputId] = stateVal;
            }
            break;
          case componentTypeNotBits:
            state[outputId] |= componentInfoCachedValMask;
            propagateComponentTrue(instanceInfo, outputId);
            break;
          default:
            //  Should not happen
            throw new UnsupportedOperationException("Unexpected component type");
        }
      }
    }
  }

  private void propagateComponent(InstanceInfo instanceInfo, int componentId, boolean value)
  {
    if ( instanceInfo.resetWatermark >= componentId )
    {
      if ( value )
      {
        propagateComponentTrue(instanceInfo, componentId);
      }
      else
      {
        propagateComponentFalse(instanceInfo, componentId);
      }
    }
  }

  public void setComponentValue(int instanceId, int propId, boolean value)
  {
    InstanceInfo instanceInfo = instances[instanceId];
    int[] state = instanceInfo.state;
    boolean currentValue = ((state[propId] & componentInfoCachedValMask) != 0);

    if ( value != currentValue )
    {
      if ( value )
      {
        state[propId] |= componentInfoCachedValMask;
        propagateComponentTrue(instanceInfo, propId);
      }
      else
      {
        state[propId] &= ~componentInfoCachedValMask;
        propagateComponentFalse(instanceInfo, propId);
      }
    }
  }

  public boolean getComponentValue(int instanceId, int propId)
  {
    int[] state = instances[instanceId].state;
    return ((state[propId] & componentInfoCachedValMask) != 0);
  }
}
