package training;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

import org.apache.log4j.Logger;

//import org.apache.hadoop.fs.Path;
//import org.apache.hadoop.io.LongWritable;
//import org.apache.hadoop.io.NullWritable;
//import org.apache.hadoop.io.Text;
//import org.apache.hadoop.mapred.JobConf;
//import org.apache.hadoop.mapred.MapReduceBase;
//import org.apache.hadoop.mapred.Mapper;
//import org.apache.hadoop.mapred.OutputCollector;
//import org.apache.hadoop.mapred.Reducer;
//import org.apache.hadoop.mapred.Reporter;
//import org.apache.hadoop.mapred.FileInputFormat;
//import org.apache.hadoop.mapred.JobClient;
//import org.apache.hadoop.mapred.lib.NullOutputFormat;

//public class CascadeSVMPartitioner extends MapReduceBase 
//	implements Mapper<LongWritable, Text, LongWritable, Text>, 
//		Reducer<LongWritable, Text, NullWritable, NullWritable> {
public class CascadeSVMPartitioner {
	public static Random rand = new Random();
	public static Logger logger = Logger.getLogger(CascadeSVMPartitioner.class);
//	public int nSubset;
//	public String subsetListPath;
//	public String workDir;
//
//	public void configure(JobConf conf) {
//		nSubset = Integer.parseInt(conf.get("nSubset"));
//		subsetListPath = conf.get("subsetListPath");
//		workDir = conf.get("workDir");
//	}
//
//	@Override
//	public void map(LongWritable key, Text value,
//			OutputCollector<LongWritable, Text> collector, Reporter reporter)
//			throws IOException {
//		collector.collect(new LongWritable(rand.nextInt(nSubset)), value);
//	}
//	
//	@Override
//	public void reduce(LongWritable key, Iterator<Text> value,
//			OutputCollector<NullWritable, NullWritable> collector, Reporter reporter)
//			throws IOException {
//		int subsetId = Integer.parseInt(key.toString());
//		String subsetPath = CascadeSVMPathHelper.getIdListPath(workDir, 0, subsetId);
//		CascadeSVMIOHelper.writeIdListHadoop(subsetPath, value);
//	}
//	
//	public static String runPartitionerJob(CascadeSVMTrainParameter parameter) throws IOException {
//		JobConf conf = new JobConf(class);
//		conf.setJobName("CascadeSVM Partitioner");
//		conf.set("nSubset", Integer.toString(parameter.nSubset));
//		conf.set("workDir", parameter.workDir);
//		String subsetListPath = CascadeSVMPathHelper.getSubsetListPath(parameter.workDir);
//		conf.set("subsetListPath", subsetListPath);
//		
//		FileInputFormat.addInputPath(conf, new Path(parameter.idlistPath));
//		
//		conf.setMapperClass(class);
//		conf.setReducerClass(class);
//		
//		conf.setOutputFormat(NullOutputFormat.class);
//		
//		JobClient.runJob(conf);
//		return subsetListPath;
//	}
	
	/* Non MapReduce METHODS */
	/**
	 * @param parameter
	 * @param idList
	 * @throws IOException
	 * Given an id list, partition it into several subsets and write them to HDFS.
	 */
	public static void partitionIdListHadoop(CascadeSVMTrainParameter parameter, ArrayList<Integer> idList) throws IOException {
		logger.info("[BEGIN]partitionIdListHadoop()");
		ArrayList< ArrayList<Integer> > subsets = partitionIdList(idList, parameter.nSubset);
		for (int i = 0; i < parameter.nSubset; i++) {
			String idListPath = CascadeSVMPathHelper.getIdListPath(parameter.workDir, 0, i);
			CascadeSVMIOHelper.writeIdListHadoop(idListPath, subsets.get(i));
		}
		logger.info("[END]partitionIdListHadoop()");
	}
	
//	public static void partitionIdListLocal(CascadeSVMTrainParameter parameter) throws IOException {
//		ArrayList<Integer> idList = CascadeSVMIOHelper.readIdListLocal(parameter.idlistPath);
//		ArrayList< ArrayList<Integer> > subsets = partitionIdList(idList, parameter.nSubset);
//		String workDir = CascadeSVMPathHelper.getLocalWorkDir();
//		for (int i = 0; i < parameter.nSubset; i++) {
//			String idListPath = CascadeSVMPathHelper.getIdListPath(workDir, 1, i);
//			CascadeSVMIOHelper.writeIdListHadoop(idListPath, subsets.get(i));
//		}
//	} 
	
	public static ArrayList< ArrayList<Integer> > partitionIdList(ArrayList<Integer> idList, int nSubset) {
		logger.info("[BEGIN]partitionIdList()");
		ArrayList< ArrayList<Integer> > subsets = new ArrayList< ArrayList<Integer> >();
		for (int i = 0; i < nSubset; i++) {
			subsets.add(new ArrayList<Integer>());
		}
		for (int i = 0; i < idList.size(); i++) {
			int subsetId = rand.nextInt(nSubset);
			subsets.get(subsetId).add(idList.get(i));
		}
		logger.info("[END]partitionIdList()");
		return subsets;
	}
}
