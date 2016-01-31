package org.ggp.base.apps.utilities;

import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_ASYNC_ENGINE_COUNT;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_CAN_MAP_HOST_MEMORY;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_CLOCK_RATE;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_COMPUTE_MODE;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_CONCURRENT_KERNELS;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_ECC_ENABLED;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_GLOBAL_MEMORY_BUS_WIDTH;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_INTEGRATED;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_KERNEL_EXEC_TIMEOUT;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_L2_CACHE_SIZE;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE1D_LAYERED_LAYERS;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE1D_LAYERED_WIDTH;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE1D_WIDTH;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE2D_HEIGHT;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE2D_LAYERED_HEIGHT;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE2D_LAYERED_LAYERS;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE2D_LAYERED_WIDTH;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE2D_WIDTH;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE3D_DEPTH;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE3D_HEIGHT;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE3D_WIDTH;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAX_BLOCK_DIM_X;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAX_BLOCK_DIM_Y;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAX_BLOCK_DIM_Z;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAX_GRID_DIM_X;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAX_GRID_DIM_Y;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAX_GRID_DIM_Z;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAX_PITCH;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAX_REGISTERS_PER_BLOCK;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAX_SHARED_MEMORY_PER_BLOCK;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAX_THREADS_PER_BLOCK;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MAX_THREADS_PER_MULTIPROCESSOR;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MEMORY_CLOCK_RATE;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_MULTIPROCESSOR_COUNT;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_PCI_BUS_ID;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_PCI_DEVICE_ID;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_PCI_DOMAIN_ID;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_SURFACE_ALIGNMENT;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_TCC_DRIVER;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_TEXTURE_ALIGNMENT;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_TOTAL_CONSTANT_MEMORY;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_UNIFIED_ADDRESSING;
import static jcuda.driver.CUdevice_attribute.CU_DEVICE_ATTRIBUTE_WARP_SIZE;
import static jcuda.driver.JCudaDriver.cuCtxCreate;
import static jcuda.driver.JCudaDriver.cuCtxSynchronize;
import static jcuda.driver.JCudaDriver.cuDeviceComputeCapability;
import static jcuda.driver.JCudaDriver.cuDeviceGet;
import static jcuda.driver.JCudaDriver.cuDeviceGetAttribute;
import static jcuda.driver.JCudaDriver.cuDeviceGetCount;
import static jcuda.driver.JCudaDriver.cuDeviceGetName;
import static jcuda.driver.JCudaDriver.cuInit;
import static jcuda.driver.JCudaDriver.cuLaunchKernel;
import static jcuda.driver.JCudaDriver.cuMemAlloc;
import static jcuda.driver.JCudaDriver.cuMemFree;
import static jcuda.driver.JCudaDriver.cuMemcpyDtoH;
import static jcuda.driver.JCudaDriver.cuMemcpyHtoD;
import static jcuda.driver.JCudaDriver.cuModuleGetFunction;
import static jcuda.driver.JCudaDriver.cuModuleLoad;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import jcuda.Pointer;
import jcuda.Sizeof;
import jcuda.driver.CUcontext;
import jcuda.driver.CUdevice;
import jcuda.driver.CUdeviceptr;
import jcuda.driver.CUfunction;
import jcuda.driver.CUmodule;
import jcuda.driver.JCudaDriver;
import jcuda.jcublas.JCublas;

public class JCudaRuntimeTest
{
  public static void main(String args[]) throws IOException
  {
    queryMain(args);
    vectorMain(args);
    matrixMultiplyMain(args);
  }

  /**
   * Entry point of this program
   *
   * @param args Not used
   */
  public static void queryMain(String args[])
  {
      JCudaDriver.setExceptionsEnabled(true);
      cuInit(0);

      // Obtain the number of devices
      int deviceCountArray[] = { 0 };
      cuDeviceGetCount(deviceCountArray);
      int deviceCount = deviceCountArray[0];
      System.out.println("Found " + deviceCount + " devices");

      for (int i = 0; i < deviceCount; i++)
      {
          CUdevice device = new CUdevice();
          cuDeviceGet(device, i);

          // Obtain the device name
          byte deviceName[] = new byte[1024];
          cuDeviceGetName(
              deviceName, deviceName.length, device);
          String name = createString(deviceName);

          // Obtain the compute capability
          int majorArray[] = { 0 };
          int minorArray[] = { 0 };
          cuDeviceComputeCapability(
              majorArray, minorArray, device);
          int major = majorArray[0];
          int minor = minorArray[0];

          System.out.println("Device " + i + ": " + name +
              " with Compute Capability " + major + "." + minor);

          // Obtain the device attributes
          int array[] = { 0 };
          List<Integer> attributes = getAttributes();
          for (Integer attribute : attributes)
          {
              String description = getAttributeDescription(attribute);
              cuDeviceGetAttribute(array, attribute, device);
              int value = array[0];

              System.out.printf("    %-52s : %d\n", description, value);
          }
      }
  }

  /**
   * Returns a short description of the given CUdevice_attribute constant
   *
   * @param attribute The CUdevice_attribute constant
   * @return A short description of the given constant
   */
  private static String getAttributeDescription(int attribute)
  {
      switch (attribute)
      {
          case CU_DEVICE_ATTRIBUTE_MAX_THREADS_PER_BLOCK:
              return "Maximum number of threads per block";
          case CU_DEVICE_ATTRIBUTE_MAX_BLOCK_DIM_X:
              return "Maximum x-dimension of a block";
          case CU_DEVICE_ATTRIBUTE_MAX_BLOCK_DIM_Y:
              return "Maximum y-dimension of a block";
          case CU_DEVICE_ATTRIBUTE_MAX_BLOCK_DIM_Z:
              return "Maximum z-dimension of a block";
          case CU_DEVICE_ATTRIBUTE_MAX_GRID_DIM_X:
              return "Maximum x-dimension of a grid";
          case CU_DEVICE_ATTRIBUTE_MAX_GRID_DIM_Y:
              return "Maximum y-dimension of a grid";
          case CU_DEVICE_ATTRIBUTE_MAX_GRID_DIM_Z:
              return "Maximum z-dimension of a grid";
          case CU_DEVICE_ATTRIBUTE_MAX_SHARED_MEMORY_PER_BLOCK:
              return "Maximum shared memory per thread block in bytes";
          case CU_DEVICE_ATTRIBUTE_TOTAL_CONSTANT_MEMORY:
              return "Total constant memory on the device in bytes";
          case CU_DEVICE_ATTRIBUTE_WARP_SIZE:
              return "Warp size in threads";
          case CU_DEVICE_ATTRIBUTE_MAX_PITCH:
              return "Maximum pitch in bytes allowed for memory copies";
          case CU_DEVICE_ATTRIBUTE_MAX_REGISTERS_PER_BLOCK:
              return "Maximum number of 32-bit registers per thread block";
          case CU_DEVICE_ATTRIBUTE_CLOCK_RATE:
              return "Clock frequency in kilohertz";
          case CU_DEVICE_ATTRIBUTE_TEXTURE_ALIGNMENT:
              return "Alignment requirement";
          case CU_DEVICE_ATTRIBUTE_MULTIPROCESSOR_COUNT:
              return "Number of multiprocessors on the device";
          case CU_DEVICE_ATTRIBUTE_KERNEL_EXEC_TIMEOUT:
              return "Whether there is a run time limit on kernels";
          case CU_DEVICE_ATTRIBUTE_INTEGRATED:
              return "Device is integrated with host memory";
          case CU_DEVICE_ATTRIBUTE_CAN_MAP_HOST_MEMORY:
              return "Device can map host memory into CUDA address space";
          case CU_DEVICE_ATTRIBUTE_COMPUTE_MODE:
              return "Compute mode";
          case CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE1D_WIDTH:
              return "Maximum 1D texture width";
          case CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE2D_WIDTH:
              return "Maximum 2D texture width";
          case CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE2D_HEIGHT:
              return "Maximum 2D texture height";
          case CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE3D_WIDTH:
              return "Maximum 3D texture width";
          case CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE3D_HEIGHT:
              return "Maximum 3D texture height";
          case CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE3D_DEPTH:
              return "Maximum 3D texture depth";
          case CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE2D_LAYERED_WIDTH:
              return "Maximum 2D layered texture width";
          case CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE2D_LAYERED_HEIGHT:
              return "Maximum 2D layered texture height";
          case CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE2D_LAYERED_LAYERS:
              return "Maximum layers in a 2D layered texture";
          case CU_DEVICE_ATTRIBUTE_SURFACE_ALIGNMENT:
              return "Alignment requirement for surfaces";
          case CU_DEVICE_ATTRIBUTE_CONCURRENT_KERNELS:
              return "Device can execute multiple kernels concurrently";
          case CU_DEVICE_ATTRIBUTE_ECC_ENABLED:
              return "Device has ECC support enabled";
          case CU_DEVICE_ATTRIBUTE_PCI_BUS_ID:
              return "PCI bus ID of the device";
          case CU_DEVICE_ATTRIBUTE_PCI_DEVICE_ID:
              return "PCI device ID of the device";
          case CU_DEVICE_ATTRIBUTE_TCC_DRIVER:
              return "Device is using TCC driver model";
          case CU_DEVICE_ATTRIBUTE_MEMORY_CLOCK_RATE:
              return "Peak memory clock frequency in kilohertz";
          case CU_DEVICE_ATTRIBUTE_GLOBAL_MEMORY_BUS_WIDTH:
              return "Global memory bus width in bits";
          case CU_DEVICE_ATTRIBUTE_L2_CACHE_SIZE:
              return "Size of L2 cache in bytes";
          case CU_DEVICE_ATTRIBUTE_MAX_THREADS_PER_MULTIPROCESSOR:
              return "Maximum resident threads per multiprocessor";
          case CU_DEVICE_ATTRIBUTE_ASYNC_ENGINE_COUNT:
              return "Number of asynchronous engines";
          case CU_DEVICE_ATTRIBUTE_UNIFIED_ADDRESSING:
              return "Device shares a unified address space with the host";
          case CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE1D_LAYERED_WIDTH:
              return "Maximum 1D layered texture width";
          case CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE1D_LAYERED_LAYERS:
              return "Maximum layers in a 1D layered texture";
          case CU_DEVICE_ATTRIBUTE_PCI_DOMAIN_ID:
              return "PCI domain ID of the device";
      }
      return "(UNKNOWN ATTRIBUTE)";
  }

  /**
   * Returns a list of all CUdevice_attribute constants
   *
   * @return A list of all CUdevice_attribute constants
   */
  private static List<Integer> getAttributes()
  {
      List<Integer> list = new ArrayList<>();
      list.add(CU_DEVICE_ATTRIBUTE_MAX_THREADS_PER_BLOCK);
      list.add(CU_DEVICE_ATTRIBUTE_MAX_BLOCK_DIM_X);
      list.add(CU_DEVICE_ATTRIBUTE_MAX_BLOCK_DIM_Y);
      list.add(CU_DEVICE_ATTRIBUTE_MAX_BLOCK_DIM_Z);
      list.add(CU_DEVICE_ATTRIBUTE_MAX_GRID_DIM_X);
      list.add(CU_DEVICE_ATTRIBUTE_MAX_GRID_DIM_Y);
      list.add(CU_DEVICE_ATTRIBUTE_MAX_GRID_DIM_Z);
      list.add(CU_DEVICE_ATTRIBUTE_MAX_SHARED_MEMORY_PER_BLOCK);
      list.add(CU_DEVICE_ATTRIBUTE_TOTAL_CONSTANT_MEMORY);
      list.add(CU_DEVICE_ATTRIBUTE_WARP_SIZE);
      list.add(CU_DEVICE_ATTRIBUTE_MAX_PITCH);
      list.add(CU_DEVICE_ATTRIBUTE_MAX_REGISTERS_PER_BLOCK);
      list.add(CU_DEVICE_ATTRIBUTE_CLOCK_RATE);
      list.add(CU_DEVICE_ATTRIBUTE_TEXTURE_ALIGNMENT);
      list.add(CU_DEVICE_ATTRIBUTE_MULTIPROCESSOR_COUNT);
      list.add(CU_DEVICE_ATTRIBUTE_KERNEL_EXEC_TIMEOUT);
      list.add(CU_DEVICE_ATTRIBUTE_INTEGRATED);
      list.add(CU_DEVICE_ATTRIBUTE_CAN_MAP_HOST_MEMORY);
      list.add(CU_DEVICE_ATTRIBUTE_COMPUTE_MODE);
      list.add(CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE1D_WIDTH);
      list.add(CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE2D_WIDTH);
      list.add(CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE2D_HEIGHT);
      list.add(CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE3D_WIDTH);
      list.add(CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE3D_HEIGHT);
      list.add(CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE3D_DEPTH);
      list.add(CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE2D_LAYERED_WIDTH);
      list.add(CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE2D_LAYERED_HEIGHT);
      list.add(CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE2D_LAYERED_LAYERS);
      list.add(CU_DEVICE_ATTRIBUTE_SURFACE_ALIGNMENT);
      list.add(CU_DEVICE_ATTRIBUTE_CONCURRENT_KERNELS);
      list.add(CU_DEVICE_ATTRIBUTE_ECC_ENABLED);
      list.add(CU_DEVICE_ATTRIBUTE_PCI_BUS_ID);
      list.add(CU_DEVICE_ATTRIBUTE_PCI_DEVICE_ID);
      list.add(CU_DEVICE_ATTRIBUTE_TCC_DRIVER);
      list.add(CU_DEVICE_ATTRIBUTE_MEMORY_CLOCK_RATE);
      list.add(CU_DEVICE_ATTRIBUTE_GLOBAL_MEMORY_BUS_WIDTH);
      list.add(CU_DEVICE_ATTRIBUTE_L2_CACHE_SIZE);
      list.add(CU_DEVICE_ATTRIBUTE_MAX_THREADS_PER_MULTIPROCESSOR);
      list.add(CU_DEVICE_ATTRIBUTE_ASYNC_ENGINE_COUNT);
      list.add(CU_DEVICE_ATTRIBUTE_UNIFIED_ADDRESSING);
      list.add(CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE1D_LAYERED_WIDTH);
      list.add(CU_DEVICE_ATTRIBUTE_MAXIMUM_TEXTURE1D_LAYERED_LAYERS);
      list.add(CU_DEVICE_ATTRIBUTE_PCI_DOMAIN_ID);
      return list;
  }

  /**
   * Creates a String from a zero-terminated string in a byte array
   *
   * @param bytes
   *            The byte array
   * @return The String
   */
  private static String createString(byte bytes[])
  {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < bytes.length; i++)
      {
          char c = (char)bytes[i];
          if (c == 0)
          {
              break;
          }
          sb.append(c);
      }
      return sb.toString();
  }

  /**
   * Entry point of this sample
   *
   * @param args Not used
   * @throws IOException If an IO error occurs
   */
  public static void vectorMain(String args[]) throws IOException
  {
      // Enable exceptions and omit all subsequent error checks
      JCudaDriver.setExceptionsEnabled(true);

      // Create the PTX file by calling the NVCC
      String ptxFileName = preparePtxFile("JCudaVectorAddKernel.cu");

      // Initialize the driver and create a context for the first device.
      cuInit(0);
      CUdevice device = new CUdevice();
      cuDeviceGet(device, 0);
      CUcontext context = new CUcontext();
      cuCtxCreate(context, 0, device);

      // Load the ptx file.
      CUmodule module = new CUmodule();
      cuModuleLoad(module, ptxFileName);

      // Obtain a function pointer to the "add" function.
      CUfunction function = new CUfunction();
      cuModuleGetFunction(function, module, "add");

      int numElements = 100000;

      // Allocate and fill the host input data
      float hostInputA[] = new float[numElements];
      float hostInputB[] = new float[numElements];
      for(int i = 0; i < numElements; i++)
      {
          hostInputA[i] = i;
          hostInputB[i] = i;
      }

      // Allocate the device input data, and copy the
      // host input data to the device
      CUdeviceptr deviceInputA = new CUdeviceptr();
      cuMemAlloc(deviceInputA, numElements * Sizeof.FLOAT);
      cuMemcpyHtoD(deviceInputA, Pointer.to(hostInputA),
          numElements * Sizeof.FLOAT);
      CUdeviceptr deviceInputB = new CUdeviceptr();
      cuMemAlloc(deviceInputB, numElements * Sizeof.FLOAT);
      cuMemcpyHtoD(deviceInputB, Pointer.to(hostInputB),
          numElements * Sizeof.FLOAT);

      // Allocate device output memory
      CUdeviceptr deviceOutput = new CUdeviceptr();
      cuMemAlloc(deviceOutput, numElements * Sizeof.FLOAT);

      // Set up the kernel parameters: A pointer to an array
      // of pointers which point to the actual values.
      Pointer kernelParameters = Pointer.to(
          Pointer.to(new int[]{numElements}),
          Pointer.to(deviceInputA),
          Pointer.to(deviceInputB),
          Pointer.to(deviceOutput)
      );

      // Call the kernel function.
      int blockSizeX = 256;
      int gridSizeX = (int)Math.ceil((double)numElements / blockSizeX);
      cuLaunchKernel(function,
          gridSizeX,  1, 1,      // Grid dimension
          blockSizeX, 1, 1,      // Block dimension
          0, null,               // Shared memory size and stream
          kernelParameters, null // Kernel- and extra parameters
      );
      cuCtxSynchronize();

      // Allocate host output memory and copy the device output
      // to the host.
      float hostOutput[] = new float[numElements];
      cuMemcpyDtoH(Pointer.to(hostOutput), deviceOutput,
          numElements * Sizeof.FLOAT);

      // Verify the result
      boolean passed = true;
      for(int i = 0; i < numElements; i++)
      {
          float expected = i+i;
          if (Math.abs(hostOutput[i] - expected) > 1e-5)
          {
              System.out.println(
                  "At index "+i+ " found "+hostOutput[i]+
                  " but expected "+expected);
              passed = false;
              break;
          }
      }
      System.out.println("Test "+(passed?"PASSED":"FAILED"));

      // Clean up.
      cuMemFree(deviceInputA);
      cuMemFree(deviceInputB);
      cuMemFree(deviceOutput);
  }

  /**
   * The extension of the given file name is replaced with "ptx".
   * If the file with the resulting name does not exist, it is
   * compiled from the given file using NVCC. The name of the
   * PTX file is returned.
   *
   * @param cuFileName The name of the .CU file
   * @return The name of the PTX file
   * @throws IOException If an I/O error occurs
   */
  private static String preparePtxFile(String cuFileName) throws IOException
  {
      int endIndex = cuFileName.lastIndexOf('.');
      if (endIndex == -1)
      {
          endIndex = cuFileName.length()-1;
      }
      String ptxFileName = cuFileName.substring(0, endIndex+1)+"ptx";
      File ptxFile = new File(ptxFileName);
      if (ptxFile.exists())
      {
          return ptxFileName;
      }

      File cuFile = new File(cuFileName);
      if (!cuFile.exists())
      {
          throw new IOException("Input file not found: "+cuFileName);
      }
      String modelString = "-m"+System.getProperty("sun.arch.data.model");
      String command =
          "nvcc " + modelString + " -ptx "+
          cuFile.getPath()+" -o "+ptxFileName;

      System.out.println("Executing\n"+command);
      Process process = Runtime.getRuntime().exec(command);

      String errorMessage =
          new String(toByteArray(process.getErrorStream()));
      String outputMessage =
          new String(toByteArray(process.getInputStream()));
      int exitValue = 0;
      try
      {
          exitValue = process.waitFor();
      }
      catch (InterruptedException e)
      {
          Thread.currentThread().interrupt();
          throw new IOException(
              "Interrupted while waiting for nvcc output", e);
      }

      if (exitValue != 0)
      {
          System.out.println("nvcc process exitValue "+exitValue);
          System.out.println("errorMessage:\n"+errorMessage);
          System.out.println("outputMessage:\n"+outputMessage);
          throw new IOException(
              "Could not create .ptx file: "+errorMessage);
      }

      System.out.println("Finished creating PTX file");
      return ptxFileName;
  }

  /**
   * Fully reads the given InputStream and returns it as a byte array
   *
   * @param inputStream The input stream to read
   * @return The byte array containing the data from the input stream
   * @throws IOException If an I/O error occurs
   */
  private static byte[] toByteArray(InputStream inputStream)
      throws IOException
  {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      byte buffer[] = new byte[8192];
      while (true)
      {
          int read = inputStream.read(buffer);
          if (read == -1)
          {
              break;
          }
          baos.write(buffer, 0, read);
      }
      return baos.toByteArray();
  }

  // ---------------------------------------------------------------------------------------------------------------

  private static final int MATRIX_SIZE = 100;
  private static final int FACTOR = 10;
  private static final int ITERATIONS = FACTOR * 50;

  private static Pointer d_A = new Pointer();
  private static Pointer d_B = new Pointer();
  private static Pointer d_C = new Pointer();

  public static void matrixMultiplyMain(String args[])
  {
    // Initialize JCublas
    JCublas.cublasInit();

    // Allocate memory on the device
    JCublas.cublasAlloc(MATRIX_SIZE * MATRIX_SIZE, Sizeof.FLOAT, d_A);
    JCublas.cublasAlloc(MATRIX_SIZE * MATRIX_SIZE, Sizeof.FLOAT, d_B);
    JCublas.cublasAlloc(MATRIX_SIZE * MATRIX_SIZE, Sizeof.FLOAT, d_C);

    // Run the test
    testSgemm();

    // Clean up
    JCublas.cublasFree(d_A);
    JCublas.cublasFree(d_B);
    JCublas.cublasFree(d_C);

    // Terminate JCublas
    JCublas.cublasShutdown();
  }

  /**
   * Test the JCublas sgemm operation for matrices of size n x x
   *
   * @param n The matrix size
   */
  public static void testSgemm()
  {
      System.out.println("Creating input data...");
      float h_A[] = createRandomFloatData(MATRIX_SIZE * MATRIX_SIZE);
      float h_B[] = createRandomFloatData(MATRIX_SIZE * MATRIX_SIZE);
      float h_C[] = createRandomFloatData(MATRIX_SIZE * MATRIX_SIZE);
      float h_C_ref[] = h_C.clone();

      long lJavaTime = 0;
      long lCublasTime = 0;

      System.out.print("Running tests...");
      for (int lii = 0; lii < ITERATIONS; lii++)
      {
        if (lii % FACTOR == 0)
        {
          lJavaTime -= System.currentTimeMillis();
          sgemmJava(MATRIX_SIZE, h_A, h_B, h_C_ref);
          lJavaTime += System.currentTimeMillis();
        }

        lCublasTime -= System.nanoTime();
        sgemmJCublas(MATRIX_SIZE, h_A, h_B, h_C);
        lCublasTime += System.nanoTime();
      }

      lCublasTime /= 1000000;
      lJavaTime *= FACTOR;

      System.out.println("");
      System.out.println("GPU was " + (lJavaTime / lCublasTime) + " times faster");
  }

  /**
   * Plain matrix multiplication, using JCublas sgemm but hard-coding alpha=1, beta=0.
   */
  private static void sgemmJCublas(int n, float A[], float B[], float C[])
  {
    int nn = n * n;

    // Copy the memory from the host to the device
    JCublas.cublasSetVector(nn, Sizeof.FLOAT, Pointer.to(A), 1, d_A, 1);
    JCublas.cublasSetVector(nn, Sizeof.FLOAT, Pointer.to(B), 1, d_B, 1);
    JCublas.cublasSetVector(nn, Sizeof.FLOAT, Pointer.to(C), 1, d_C, 1);

    // Execute sgemm
    JCublas.cublasSgemm('n', 'n', n, n, n, 1.0f, d_A, n, d_B, n, 0.0f, d_C, n);

    // Copy the result from the device to the host
    JCublas.cublasGetVector(nn, Sizeof.FLOAT, d_C, 1, Pointer.to(C), 1);
  }

  /**
   * Plain matrix multiplication (C = AB) in Java.
   */
  private static void sgemmJava(int n, float A[], float B[], float C[])
  {
      for (int i = 0; i < n; ++i)
      {
          for (int j = 0; j < n; ++j)
          {
              float prod = 0;
              for (int k = 0; k < n; ++k)
              {
                  prod += A[k * n + i] * B[j * n + k];
              }
              C[j * n + i] = prod;
          }
      }
  }


  /**
   * Creates an array of the specified size, containing some random data
   */
  private static float[] createRandomFloatData(int n)
  {
      Random random = new Random();
      float x[] = new float[n];
      for (int i = 0; i < n; i++)
      {
          x[i] = random.nextFloat();
      }
      return x;
  }

  /**
   * Compares the given result against a reference, and returns whether the
   * error norm is below a small epsilon threshold
   */
  private static boolean isCorrectResult(float result[], float reference[])
  {
      float errorNorm = 0;
      float refNorm = 0;
      for (int i = 0; i < result.length; ++i)
      {
          float diff = reference[i] - result[i];
          errorNorm += diff * diff;
          refNorm += reference[i] * result[i];
      }
      errorNorm = (float) Math.sqrt(errorNorm);
      refNorm = (float) Math.sqrt(refNorm);
      if (Math.abs(refNorm) < 1e-6)
      {
          return false;
      }
      return (errorNorm / refNorm < 1e-6f);
  }
}
