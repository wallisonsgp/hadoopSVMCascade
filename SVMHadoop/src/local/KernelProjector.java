package local;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;


public class KernelProjector {
	
	public float[][] projectHadoop(FileSystem fs, String kerneldir, ArrayList<Integer> sampleIDList, int kernelDim) {
		sampleIDList = arrayMinusOne(sampleIDList);
		int sizeOfFloat = (int) (Float.SIZE/8.0d);
		//0) Sort the sample ID List
		Collections.sort(sampleIDList);
		
		//1) Calculate the skip array according to the IDList
		int[] skiparary = new int[sampleIDList.size()];
		int endLineSkip = -1;
		skiparary[0] = (sampleIDList.get(0) - 0)*sizeOfFloat;
		for(int i = 1 ; i < sampleIDList.size() ; i++) {
			skiparary[i] = (sampleIDList.get(i) - sampleIDList.get(i-1) -1)*sizeOfFloat; 
		}
		endLineSkip = (kernelDim - sampleIDList.get(sampleIDList.size()-1) - 1)*sizeOfFloat;
		int wholeLineSkip = kernelDim * sizeOfFloat;
		
		
		//2) Find out the kernel chunks that needs to access
		
		ArrayList<FileStatus> sortedKernel = null;
		int chunkSize = -1;
		try {
			sortedKernel = sortKernelPathHadoop(fs, kerneldir);
			chunkSize =  getChunkSize(sortedKernel.get(0).getPath().getName());
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		float[][] result = new float[sampleIDList.size()][sampleIDList.size()];
		
		//3) Project the kernel values
		BufferedInputStream br = null;
		
		
		int curKernelID = -1;
		byte[] floatbyte = new byte[4];
		int lastLineInKernel = 0;
		try {
			for(int i = 0 ; i < sampleIDList.size() ; ) {
				if(sampleIDList.get(i) < (curKernelID+1)*chunkSize) {
					int numLine2Skip = sampleIDList.get(i)%chunkSize - lastLineInKernel;
					safeSkip(br, numLine2Skip*wholeLineSkip);
					for(int j = 0 ; j < skiparary.length ; j++) {
						safeSkip(br, skiparary[j]);
						br.read(floatbyte);
						result[i][j] = KernelCalculator.toIEEE754Float(floatbyte);
					}
					safeSkip(br, endLineSkip);
					lastLineInKernel = sampleIDList.get(i)%chunkSize + 1;
					i++;
				} else {
					if(br != null) br.close();
					curKernelID++;		//move to next kernel chunk
					br = new BufferedInputStream(fs.open(sortedKernel.get(curKernelID).getPath()));
					lastLineInKernel = 0;
					continue;	//without increasing i
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		
		
		/*for(int i = 0 ; i < result.length ; i++) {
			for(int j = 0 ; j < result[i].length ; j++) {
				System.out.print(result[i][j] + " ");
			}
			System.out.print("\n");
		}*/
		
		return result;
	}
	
	
	public float[][] project(File kerneldir, ArrayList<Integer> sampleIDList, int kernelDim) {
		sampleIDList = arrayMinusOne(sampleIDList);
		int sizeOfFloat = (int) (Float.SIZE/8.0d);
		//0) Sort the sample ID List
		Collections.sort(sampleIDList);
		
		//1) Calculate the skip array according to the IDList
		int[] skiparary = new int[sampleIDList.size()];
		int endLineSkip = -1;
		skiparary[0] = (sampleIDList.get(0) - 0)*sizeOfFloat;
		for(int i = 1 ; i < sampleIDList.size() ; i++) {
			skiparary[i] = (sampleIDList.get(i) - sampleIDList.get(i-1) -1)*sizeOfFloat; 
		}
		endLineSkip = (kernelDim - sampleIDList.get(sampleIDList.size()-1) - 1)*sizeOfFloat;
		int wholeLineSkip = kernelDim * sizeOfFloat;
		
		
		//2) Find out the kernel chunks that needs to access
		
		ArrayList<File> sortedKernel = null;
		int chunkSize = -1;
		try {
			sortedKernel = sortKernelPath(kerneldir);
			chunkSize =  getChunkSize(sortedKernel.get(0).getName());
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		float[][] result = new float[sampleIDList.size()][sampleIDList.size()];
		
		//3) Project the kernel values
		BufferedInputStream br = null;
		
		
		int curKernelID = -1;
		byte[] floatbyte = new byte[4];
		int lastLineInKernel = 0;
		try {
			for(int i = 0 ; i < sampleIDList.size() ; ) {
				if(sampleIDList.get(i) < (curKernelID+1)*chunkSize) {
					int numLine2Skip = sampleIDList.get(i)%chunkSize - lastLineInKernel;
					safeSkip(br, numLine2Skip*wholeLineSkip);
					for(int j = 0 ; j < skiparary.length ; j++) {
						safeSkip(br, skiparary[j]);
						br.read(floatbyte);
						result[i][j] = KernelCalculator.toIEEE754Float(floatbyte);
					}
					safeSkip(br, endLineSkip);
					lastLineInKernel = sampleIDList.get(i)%chunkSize + 1;
					i++;
				} else {
					if(br != null) br.close();
					curKernelID++;		//move to next kernel chunk
					br = new BufferedInputStream(new FileInputStream(sortedKernel.get(curKernelID)));
					lastLineInKernel = 0;
					continue;	//without increasing i
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		
		
		/*for(int i = 0 ; i < result.length ; i++) {
			for(int j = 0 ; j < result[i].length ; j++) {
				System.out.print(result[i][j] + " ");
			}
			System.out.print("\n");
		}*/
		
		return result;
	}
	
	
	
	private void safeSkip(BufferedInputStream br, long numOfBytes) throws IOException {
		long actualSkipedByte = 0;
		while(actualSkipedByte != numOfBytes) {
			actualSkipedByte += br.skip(numOfBytes-actualSkipedByte);
		}
	}
	
	private ArrayList<FileStatus> sortKernelPathHadoop(FileSystem fs, String kerneldir) throws Exception {
		
		FileStatus[] status = fs.listStatus(new Path(kerneldir));
		ArrayList<FileStatus> sortedFiles = new ArrayList<FileStatus>();
		ArrayList<String> a_feature_binary_name = new ArrayList<String>();
		for (int i = 0; i < status.length; i++) {
			String thispath = status[i].getPath().getName();
			if (thispath.indexOf("inpart") != -1) {
				a_feature_binary_name.add(thispath.toString());
			}
		}
		
		ArrayList<Integer> kernel_ids = new ArrayList<Integer>();
		
		
		// extract small file id from the a_feature_binary file name
		for (int i = 0; i < status.length; i++) {
			String tfilename = status[i].getPath().getName();
			int id = -1;
			try{
				id = Integer.parseInt(tfilename.substring(
						tfilename.indexOf("-c") + "-c".length(),
						tfilename.lastIndexOf("-")));
			} catch(Exception e) {
				throw new Exception("The input A feature file name is in bad format:" + tfilename +"\n" + e.toString());
			}
			kernel_ids.add(id);
		}

		// sort according to the id! index starting from 0
		for (int i = 0; i < status.length; i++) {
			boolean found = false;
			int j = 0;
			for (; j < kernel_ids.size(); j++) {
				if (i == kernel_ids.get(j)) {
					found = true;
					break;
				}
			}
			if (found) {
				sortedFiles.add(status[j]);
			} else {
				throw new Exception(
						"the input A feature is not consecutive! Missing:" + i);
			}
		}
		
		
		
		//check the chunk size.
		//make sure all kernels except the last one have the same line number
		int chunkSize = -1;
		
		for (int i = 0; i < sortedFiles.size()-1; i++) {
			String tfilename = status[i].getPath().getName();
			int thisSize = getChunkSize(tfilename);
			if(chunkSize == -1) chunkSize = thisSize;
			if(thisSize != chunkSize) throw new Exception("Bad chunk size : " + status[i].getPath().getName());
		}
		
		return sortedFiles;
	}
	
	
	private ArrayList<File> sortKernelPath(File kerneldir) throws Exception {
		File[] status = kerneldir.listFiles();
		ArrayList<File> sortedFiles = new ArrayList<File>();
		ArrayList<String> a_feature_binary_name = new ArrayList<String>();
		for (int i = 0; i < status.length; i++) {
			String thispath = status[i].getName();
			if (thispath.indexOf("inpart") != -1) {
				a_feature_binary_name.add(thispath.toString());
			}
		}
		
		ArrayList<Integer> kernel_ids = new ArrayList<Integer>();
		
		
		// extract small file id from the a_feature_binary file name
		for (int i = 0; i < status.length; i++) {
			String tfilename = status[i].getName();
			int id = -1;
			try{
				id = Integer.parseInt(tfilename.substring(
						tfilename.indexOf("-c") + "-c".length(),
						tfilename.lastIndexOf("-")));
			} catch(Exception e) {
				throw new Exception("The input A feature file name is in bad format:" + tfilename +"\n" + e.toString());
			}
			kernel_ids.add(id);
		}

		// sort according to the id! index starting from 0
		for (int i = 0; i < status.length; i++) {
			boolean found = false;
			int j = 0;
			for (; j < kernel_ids.size(); j++) {
				if (i == kernel_ids.get(j)) {
					found = true;
					break;
				}
			}
			if (found) {
				sortedFiles.add(status[j]);
			} else {
				throw new Exception(
						"the input A feature is not consecutive! Missing:" + i);
			}
		}
		
		
		
		//check the chunk size.
		//make sure all kernels except the last one have the same line number
		int chunkSize = -1;
		
		for (int i = 0; i < sortedFiles.size()-1; i++) {
			String tfilename = status[i].getName();
			int thisSize = getChunkSize(tfilename);
			if(chunkSize == -1) chunkSize = thisSize;
			if(thisSize != chunkSize) throw new Exception("Bad chunk size : " + status[i].getName());
		}
		
		return sortedFiles;
	}
	
	
	
	
	private int getChunkSize(String kernelFileName) throws Exception {
		int thisSize = -1;
		try{
			thisSize = Integer.parseInt(kernelFileName.substring(
					kernelFileName.indexOf("-r") + "-r".length()));
		
		} catch(NumberFormatException e) {
			throw new Exception("The input A feature file name is in bad format:" + kernelFileName +"\n" + e.toString());
		}
		return thisSize;
	}
	
	
	/**
	 * idlist contains the ID starting from 1
	 * We need to convert the ID starting from 0
	 * @param idlist
	 */
	private ArrayList<Integer> arrayMinusOne(ArrayList<Integer> idlist) {
		ArrayList<Integer> internal_idlist = new ArrayList<Integer>();
		for(int i = 0 ; i < idlist.size() ; i++) {
			internal_idlist.add(idlist.get(i)-1);
		}
		return internal_idlist;
	}
	

	/**
	 * @param args
	 * @throws Exception 
	 */
	public static void main(String[] args) throws Exception {
		KernelProjector worker = new KernelProjector();
		ArrayList<Integer> c = new ArrayList<Integer>();
		c.add(2);
		c.add(7);
		c.add(9);
		c.add(16);
	

		worker.project(new File("C:\\Users\\lujiang\\Downloads\\kernelcomputation_test (1)\\kernel_truth"), c, 30000);
		//worker.projectHadoop(FileSystem.get(new Configuration()), "C:\\Users\\lujiang\\Downloads\\kernelcomputation_test (1)\\kernel_truth", c, 30000);

	}

}
