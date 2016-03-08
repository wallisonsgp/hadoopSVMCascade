package training;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.mapred.lib.NullOutputFormat;
import org.apache.log4j.Logger;

public class CascadeSVMScheduler extends MapReduceBase 
		implements Mapper<IntWritable, Text, IntWritable, Text>,
		Reducer<IntWritable, Text, NullWritable, NullWritable> {
	public static Logger logger = Logger.getLogger(CascadeSVMScheduler.class);
	public JobConf conf;
	
	public void configure(JobConf conf) {
		this.conf = conf;
	} 
	
	@Override
	public void map(IntWritable key, Text value, OutputCollector<IntWritable, Text> output, Reporter reporter)
			throws IOException {
		logger.info("[BEGIN]map()");
		CascadeSVMSchedulerParameter parameter = null;
		try {
			parameter = new CascadeSVMSchedulerParameter(value.toString());
		} catch (CascadeSVMParameterFormatError e) {
			logger.info(e.toString());
			logger.info(value.toString());
			logger.info(CascadeSVMSchedulerParameter.helpText);
			return ;
		}
		ArrayList<Integer> subsetIds = mergeSVIntoSubsetsHadoop(parameter);
		String nodeParameterPath = generateNodeParametersHadoop(parameter, subsetIds);
		int startId = 0;
		int endId = parameter.nSubset - 1;
		CascadeSVMNode.runNodeJob(parameter, nodeParameterPath, startId, endId, conf);
		output.collect(key, value);
		logger.info("[END]map()");
	} 
	
	@Override
	public void reduce(IntWritable key, Iterator<Text> value,
			OutputCollector<NullWritable, NullWritable> output, Reporter reporter)
			throws IOException {
		logger.info("[BEGIN]reduce()");
		if (value.hasNext()) {
			CascadeSVMSchedulerParameter parameter;
			try {
				parameter = new CascadeSVMSchedulerParameter(value.next().toString());
			} catch (CascadeSVMParameterFormatError e) {
				logger.info(e.toString());
				logger.info(CascadeSVMSchedulerParameter.helpText);
				return ;
			}
			double LD = scheduleHadoop(parameter, reporter);
			// test convergence
			if (parameter.iterationId < parameter.max_iter && Math.abs(LD - parameter.lastLD) / parameter.lastLD > parameter.epsilon) {
				CascadeSVMSchedulerParameter newParameter = new CascadeSVMSchedulerParameter(parameter);
				newParameter.iterationId++;
				newParameter.lastLD = LD;
				int lastId = 2 * newParameter.nSubset - 2;
				newParameter.lastSVPath = CascadeSVMPathHelper.getSVPath(parameter.workDir, parameter.iterationId, lastId);
				String schedulerParametersPath = CascadeSVMPathHelper.getSchedulerParameterPath(parameter.workDir);
				CascadeSVMIOHelper.writeSchedulerParameterHadoop(schedulerParametersPath, newParameter);
				runSchedulerJob(schedulerParametersPath, conf);
			} 
		}
		logger.info("[END]reduce()");
	}

	public static void runSchedulerJob(String schedulerParameterPath, JobConf oldConf) throws IOException {
		logger.info("[BEGIN]runSchedulerJob()");
		JobConf conf = new JobConf(oldConf, CascadeSVMScheduler.class);
		conf.setJobName("CascadeSVM Scheduler");
		FileInputFormat.setInputPaths(conf, new Path(schedulerParameterPath));
		conf.setMapperClass(CascadeSVMScheduler.class);
		conf.setReducerClass(CascadeSVMScheduler.class);
		conf.setMapOutputKeyClass(IntWritable.class);
		conf.setMapOutputValueClass(Text.class);
		conf.setInputFormat(SequenceFileInputFormat.class);
		conf.setOutputFormat(NullOutputFormat.class);
		
    	conf.set("mapred.child.java.opts", "-Xmx1200m");		//cannot be too large <2000m
		
		conf.set("mapred.cluster.map.memory.mb","2000");
		conf.set("mapred.cluster.reduce.memory.mb","2000");
		
		conf.set("mapred.job.map.memory.mb","2000");
		conf.set("mapred.job.reduce.memory.mb","2000");
		conf.set("mapred.tasktracker.map.tasks.maximum","1");
		conf.set("mapred.map.max.attempts","8");
		conf.set("mapred.reduce.max.attempts","8");

		conf.setNumMapTasks(1);
		conf.setNumReduceTasks(1);
		
		JobClient client = new JobClient(conf);
		client.submitJob(conf);
		logger.info("[END]runSchedulerJob()");
	}
	
	/**
	 * @param parameter
	 * @return subsetIds, [0..nSubset]
	 * @throws IOException
	 */
	public static ArrayList<Integer> mergeSVIntoSubsetsHadoop(CascadeSVMSchedulerParameter parameter) 
			throws IOException {
		/* It will write two kinds of files
		 * ${outputPathPrefix}.list: a list of id list path
		 * ${outputPathPrefix}.#: id list, # in [0..n)
		 */
		logger.info("[BEGIN]mergeSVIntoSubsetsHadoop()");
		if (parameter.lastSVPath.equals(CascadeSVMSchedulerParameter.NULL_LIST_PATH)) {
			logger.info("No support vector to be merged.");
			for (int i = 0; i < parameter.nSubset; i++) {
				String srcPath = CascadeSVMPathHelper.getIdListPath(parameter.workDir, 0, i);
				String desPath = CascadeSVMPathHelper.getIdListPath(parameter.workDir, parameter.iterationId, i);
				CascadeSVMIOHelper.copyFileHadoop(srcPath, desPath);
			}
		}
		else {
			ArrayList<Integer> SVIdList = CascadeSVMIOHelper.readIdListHadoop(parameter.lastSVPath); 
			
			for (int i = 0; i < parameter.nSubset; i++) {
				String subsetIdPath = CascadeSVMPathHelper.getIdListPath(parameter.workDir, 0, i);
				ArrayList<Integer> subsetIdList = CascadeSVMIOHelper.readIdListHadoop(subsetIdPath);
				ArrayList<Integer> mergedIdList = mergeIdLists(subsetIdList, SVIdList);
				String mergedIdListPath = CascadeSVMPathHelper.getIdListPath(parameter.workDir, parameter.iterationId, i);
				CascadeSVMIOHelper.writeIdListHadoop(mergedIdListPath, mergedIdList);
			}
		}
		ArrayList<Integer> subsetIds = new ArrayList<Integer>();
		for (int i = 0; i < parameter.nSubset; i++)
			subsetIds.add(i);
		logger.info("[END]mergeSVIntoSubsets()");
		return subsetIds;
	}

	public double scheduleHadoop(CascadeSVMSchedulerParameter parameter, Reporter reporter) throws IOException {
		logger.info("[BEGIN]schedulerHadoop()");
		double LD_max = 0;
		ArrayList<Integer> runningIds = new ArrayList<Integer>();
		ArrayList<Integer> finishIds = new ArrayList<Integer>();
		for (int i = 0; i < parameter.nSubset; i++)
			runningIds.add(new Integer(i));
		int nowId = parameter.nSubset;
		while (runningIds.size() != 0 || finishIds.size() != 1) {
			for (int i = 0; i < runningIds.size();) {
				try {
					String LDPath = CascadeSVMPathHelper.getLDPath(parameter.workDir, parameter.iterationId, runningIds.get(i).intValue());
					double LD = CascadeSVMIOHelper.readLDHadoop(LDPath);
					if (LD > LD_max) LD_max = LD;
					logger.info("LD_max = " + Double.toString(LD_max));
					finishIds.add(runningIds.get(i));
					runningIds.remove(i);
				}
				catch (IOException e) {
					i++;
				}
			}
			if (finishIds.size() >= 2) {
				ArrayList<Integer> newSubsetIds = mergeSubsetsHadoop(finishIds, parameter, nowId);
				runningIds.addAll(newSubsetIds);
				String nodeParameterPath = generateNodeParametersHadoop(parameter, newSubsetIds);
				int startId = newSubsetIds.get(0).intValue();
				int endId   = newSubsetIds.get(newSubsetIds.size() - 1).intValue();
				nowId = endId + 1;
				CascadeSVMNode.runNodeJob(parameter, nodeParameterPath, startId, endId, conf);
			}
			
			if (runningIds.size() == 0 && finishIds.size() == 1)
				break;
			
			reporter.setStatus("Sleeping..zzz...");
			try {
				Thread.sleep(15*1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			reporter.setStatus("GET UP!");
		}
		logger.info("LD = " + Double.toString(LD_max));
		logger.info("[END]schedulerHadoop()");
		return LD_max;
	}
	
	public ArrayList<Integer> mergeSubsetsHadoop(ArrayList<Integer> finishIds, CascadeSVMSchedulerParameter parameter, int nowId) throws IOException {
		logger.info("[BEGIN]mergeSubsetsHadoop()");
		ArrayList<Integer> newSubsetIds = new ArrayList<Integer>();
		
		while (finishIds.size() >= 2) {
			String subsetIdListPath1 = CascadeSVMPathHelper.getSVPath(parameter.workDir, parameter.iterationId, finishIds.get(0));
			String subsetIdListPath2 = CascadeSVMPathHelper.getSVPath(parameter.workDir, parameter.iterationId, finishIds.get(1));
			String mergedIdListPath  = CascadeSVMPathHelper.getIdListPath(parameter.workDir, parameter.iterationId, nowId);
			
			ArrayList<Integer> subsetIdList1 = CascadeSVMIOHelper.readIdListHadoop(subsetIdListPath1);
			ArrayList<Integer> subsetIdList2 = CascadeSVMIOHelper.readIdListHadoop(subsetIdListPath2);
			ArrayList<Integer> mergedIdList  = mergeIdLists(subsetIdList1, subsetIdList2);
			
			CascadeSVMIOHelper.writeIdListHadoop(mergedIdListPath, mergedIdList);
			
			finishIds.remove(0);
			finishIds.remove(0);
			newSubsetIds.add(nowId);
			
			nowId++;
		}
		logger.info("[END]mergeSubsetsHadoop()");
		return newSubsetIds;
	}
	
	public String generateNodeParametersHadoop(CascadeSVMSchedulerParameter parameter, ArrayList<Integer> newSubsetIds) throws IOException {
		logger.info("[BEGIN]generateNodeParameterHadoop()");
		ArrayList<CascadeSVMNodeParameter> nodeParameters = new ArrayList<CascadeSVMNodeParameter>();
		for (int i = 0; i < newSubsetIds.size(); i++) {
			 CascadeSVMNodeParameter nodeParameter = new CascadeSVMNodeParameter(parameter);
			 nodeParameter.modelPath  = CascadeSVMPathHelper.getModelPath(parameter.workDir, parameter.iterationId, newSubsetIds.get(i));
			 nodeParameter.SVPath     = CascadeSVMPathHelper.getSVPath(parameter.workDir, parameter.iterationId, newSubsetIds.get(i));
			 nodeParameter.LDPath     = CascadeSVMPathHelper.getLDPath(parameter.workDir, parameter.iterationId, newSubsetIds.get(i));
			 nodeParameter.idlistPath = CascadeSVMPathHelper.getIdListPath(parameter.workDir, parameter.iterationId, newSubsetIds.get(i));
			 nodeParameters.add(nodeParameter);
		}
		String nodeParameterPath = CascadeSVMPathHelper.getNodeParameterPath(parameter.workDir);
		CascadeSVMIOHelper.writeNodeParametersHadoop(nodeParameterPath, nodeParameters);
		logger.info("[END]generateNodeParameterHadoop()");
		return nodeParameterPath;
	}
	
	public static ArrayList<Integer> mergeIdLists(ArrayList<Integer> idList1, ArrayList<Integer> idList2) {
		logger.info("[BEGIN]mergeIdLists()");
		HashSet<Integer> idSet = new HashSet<Integer>(idList1);
		idSet.addAll(idList2);
		ArrayList<Integer> idList = new ArrayList<Integer>(idSet);
		Collections.sort(idList);
		logger.info("[END]mergeIdLists()");
		return idList;
	}
	
	
	/* Local method
	 */
	
//	public static String mergeSVIntoSubsets(String subsetListPath, String SVPath, String outputDir) 
//			throws IOException {
//		/* It will write two kinds of files
//		 * ${outputPathPrefix}.list: a list of id list path
//		 * ${outputPathPrefix}.#: id list, # in [0..n)
//		 */
//		if (verbose) logger.info("[BEGIN] mergeSVIntoSubsets");
//		if (SVPath == "") {
//			if (verbose) logger.info("No support vector to be merged.");
//			if (verbose) logger.info("[END] mergeSVIntoSubsets");
//			return subsetListPath;
//		}
//		BufferedReader subsetsListReader = new BufferedReader(new FileReader(subsetListPath));
//		String outputListPath = CascadeSVMPathHelper.getSubsetListPath(outputDir);
//		BufferedWriter outputListWriter = new BufferedWriter(new FileWriter(outputListPath));
//		int id = 0;
//		String idListPath;
//		while ((idListPath = subsetsListReader.readLine()) != null) {
//			String outputPath = CascadeSVMPathHelper.getIdListPath(outputDir, 0, id);
//			outputListWriter.write(outputPath);
//			outputListWriter.newLine();
//			mergeTwoSubsets(idListPath, SVPath, outputPath);
//			id++;
//		}
//		outputListWriter.close();
//		subsetsListReader.close();
//		if (verbose) logger.info("[END] mergeSVIntoSubsets"); 
//		return outputListPath;
//	}
//
//	public static void schedule(String argLine, String workDir) throws IOException, CascadeSVMParameterFormatError {
//		if (verbose) logger.info("[BEGIN] schedule");
//		CascadeSVMSchedulerParameter param = new CascadeSVMSchedulerParameter(argLine);
//		double LD_max = 0;
//		ArrayList<Integer> runningIds = new ArrayList<Integer>();
//		ArrayList<Integer> finishIds = new ArrayList<Integer>();
//		for (int i = 0; i < param.nSubset; i++)
//			runningIds.add(new Integer(i));
//		int nowId = param.nSubset;
//		while (runningIds.size() != 0 || finishIds.size() != 1) {
//			for (int i = 0; i < runningIds.size();) {
//				try {
//					String LDPath = CascadeSVMPathHelper.getLDPath(workDir, param.iterationId, runningIds.get(i).intValue());
//					BufferedReader LDReader = new BufferedReader(new FileReader(LDPath));
//					double LD_temp = Double.parseDouble(LDReader.readLine().trim());
//					if (LD_temp > LD_max) {
//						LD_max = LD_temp;
//					}
//					LDReader.close();
//					finishIds.add(runningIds.get(i));
//					runningIds.remove(i);
//				}
//				catch (IOException e) {
//					i++;
//				}
//			}
//			if (finishIds.size() > 1) {
//				String mergedListPath = mergeSubsets(finishIds, workDir, param.iterationId, nowId);
//				int lastId = nowId;
//				nowId = nowId + finishIds.size() / 2;
//				for (int i = lastId; i < nowId; i++) {
//					finishIds.remove(0);
//					finishIds.remove(0);
//					runningIds.add(new Integer(i));
//				}
//				// Local verbose:
//				(new Thread(new FakeSubmitJob(param.kernelPath, param.labelPath, mergedListPath, 5, workDir))).BEGIN();
//			}
//			try {
//				Thread.sleep(30*1000);
//			} catch (InterruptedException e) {
//				e.printStackTrace();
//			}
//		}
//		if (Math.abs((LD_max - param.lastLD)/ param.lastLD) > 1e-5) {
//			if (verbose) logger.info("LD = " + Double.toString(LD_max)); 
//		}
//		if (verbose) logger.info("[END] schedule");
//	}
//	
//	public static String mergeSubsets(ArrayList<Integer> ids, String workDir, int iter, int BEGINId) throws IOException {
//		if (verbose) logger.info("[BEGIN] mergeSubsets"); 
//		String mergedListPath = CascadeSVMPathHelper.getSubsetListPath(workDir);
//		BufferedWriter mergedListWriter = new BufferedWriter(new FileWriter(mergedListPath));
//		
//		int i = 0;
//		int nowId = BEGINId;
//		while (i + 1 < ids.size()) {
//			String subIdPath1 = CascadeSVMPathHelper.getSVPath(workDir, iter, ids.get(i));
//			String subIdPath2 = CascadeSVMPathHelper.getSVPath(workDir, iter, ids.get(i+1));
//			i += 2;
//			String unionIdPath = CascadeSVMPathHelper.getIdListPath(workDir, iter, nowId);
//			nowId++;
//			mergeTwoSubsets(subIdPath1, subIdPath2, unionIdPath);
//			mergedListWriter.write(unionIdPath);
//			mergedListWriter.newLine();
//		}
//		
//		mergedListWriter.close();
//		if (verbose) logger.info("[END] mergeSubsets"); 
//		return mergedListPath;
//	}
//	
//	public static void mergeTwoSubsets(String subPath1, String subPath2, String unionPath)
//			throws IOException {
//		if (verbose) logger.info("[BEGIN] mergeTwoSubsets");
//		ArrayList<Integer> idList1 = CascadeSVMIOHelper.readIdListLocal(subPath1);
//		ArrayList<Integer> idList2 = CascadeSVMIOHelper.readIdListLocal(subPath2);
//		HashSet<Integer> idSet = new HashSet<Integer>(idList1);
//		idSet.addAll(idList2);
//		ArrayList<Integer> idList = new ArrayList<Integer>(idSet);
//		// Collections.sort(idList);
//		CascadeSVMIOHelper.writeIdListLocal(unionPath, idList);
//		if (verbose) logger.info("[END] mergeTwoSubsets");
//	}
//	
//	// Test
//	public static class FakeSubmitJob extends Thread {
//		String kernelPath;
//		String labelPath;
//		String mergedListPath;
//		int nrFold;
//		String workDir;
//		FakeSubmitJob(String kernelPath, String labelPath, String mergedListPath, int nrFold, String workDir) {
//			this.kernelPath = kernelPath;
//			this.labelPath = labelPath;
//			this.mergedListPath = mergedListPath;
//			this.nrFold = nrFold;
//			this.workDir = workDir;
//		}
//		
//		public void run() {
//			if (verbose) logger.info("[BEGIN] FakeSubmitJob.run()");
//			try {
//				BufferedReader mergedListReader = new BufferedReader(new FileReader(mergedListPath));
//				String idListPath;
//				while ((idListPath = mergedListReader.readLine()) != null) {
//					String[] names = idListPath.split("\\.");
//					int iter = Integer.parseInt(names[names.length-2]);
//					int id = Integer.parseInt(names[names.length-1]);
//					String modelOutputPath = CascadeSVMPathHelper.getModelPath(workDir, iter, id);
//					String SVIdOutputPath = CascadeSVMPathHelper.getSVPath(workDir, iter, id);
//					CascadeSVMNode singleSVM = new CascadeSVMNode(kernelPath, labelPath, idListPath, nrFold, modelOutputPath, SVIdOutputPath);
//					String LDPath = CascadeSVMPathHelper.getLDPath(workDir, iter, id);
//					singleSVM.writeLD(LDPath);
//				}
//				mergedListReader.close();
//			} catch (IOException e) {
//				e.printStackTrace();
//			}
//			if (verbose) logger.info("[END] FakeSubmitJob.run()");
//		}
//	}
//	/**
//	 * @param args
//	 */
//	public static void main(String[] args) {
//		logger = Logger.getLogger(class);
//		
//		String iter = Integer.toString(1);
//		String LD = Double.toString(0);
//		String SVPath = "";
//		String subsetSize = Integer.toString(8);
//		String subsetListPath = "/home/lightxu/Work/cascadesvm_experiment/scheduler/subsets.list";
//		String kernelPath = "/home/lightxu/Work/cascadesvm_experiment/scheduler/kernel.txt";
//		String labelPath = "/home/lightxu/Work/cascadesvm_experiment/scheduler/label.txt";
//		String nrFold = Integer.toString(5);
//		String argLine = iter + " " + LD + " " + SVPath + " " + subsetSize + " " + subsetListPath + " " + kernelPath + " " + labelPath + " " + nrFold;
//		String workDir = "/home/lightxu/Work/cascadesvm_experiment/scheduler";
//		
//		try {
//			BufferedReader reader = new BufferedReader(new FileReader(subsetListPath));
//			for (int i = 7; i < 8; i++) {
//					String idListPath = reader.readLine().trim();
//					String modelOutputPath = CascadeSVMPathHelper.getModelPath(workDir, 1, i);
//					String SVIdOutputPath = CascadeSVMPathHelper.getSVPath(workDir, 1, i);
//					String LDOutputPath = CascadeSVMPathHelper.getLDPath(workDir, 1, i);
//					CascadeSVMNode svm = new CascadeSVMNode(kernelPath, labelPath, idListPath, 5, modelOutputPath, SVIdOutputPath);
//					svm.writeLD(LDOutputPath);
//			}
//			reader.close();
//			schedule(argLine, workDir);
//		} catch (IOException e) {
//			e.printStackTrace();
//		} catch (CascadeSVMParameterFormatError e) {
//			e.printStackTrace();
//		}
//	}

}
