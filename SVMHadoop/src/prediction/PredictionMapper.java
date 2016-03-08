package prediction;


import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;

import local.KernelCalculator;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.mapred.TextOutputFormat;
import org.apache.log4j.Logger;

import beans.FloatMatrix;
import beans.KernelRow;
import beans.KernelRowArrayWritable;
import beans.KernelRowWritable;

@SuppressWarnings("deprecation")
public class PredictionMapper {

	public static class SVMHadoopMapper extends MapReduceBase implements
			Mapper<IntWritable, KernelRowArrayWritable, IntWritable, Text> {
		
		protected static Logger logger;
	
		private FileSystem fs;
		private KernelCalculator calculator;
		private Path[] pathes;					
		private String buffer;					//the directory of the buffer
		private int row_a;						//the number of row in A matrix
		private String kernel_type;				//kernel type
		public boolean debug = true;			
	
	
	    
		public void configure(JobConf job) {
			try {
				logger = Logger.getLogger(SVMHadoopMapper.class);
				if(debug)	logger.info("memory for this task:" + job.getMemoryForMapTask() +"\r\n");
				if(debug)	logger.info("memory for mapper:" + job.getMemoryForMapTask() +"\r\n");
				if(debug)	logger.info("memory for reducer:" + job.getMemoryForReduceTask() +"\r\n");
				row_a = Integer.parseInt(job.get("row_a"));
				pathes = this.topathes(job.get("in_a_pathes"));
				
				buffer = job.get("kernelmatrix_buffer");
				
				
				kernel_type = job.get("kernel_type");
				if(debug)	logger.info("row_a:" + row_a +"\r\n");
				if(debug)	logger.info("pathes[0]:" + pathes[0] +"\r\n");
				if(debug)	logger.info("pathes[pathes.length-1]:" + pathes[pathes.length-1] +"\r\n");
				if(debug)   logger.info("kernel_type:" + kernel_type +"\r\n");
				
				if(!kernel_type.equals("chi2") && !kernel_type.equals("rbf")) {
					logger.info("Unknown kernel type\r\n");
					logger.info("kernel_type:" + kernel_type +"\r\n");
					System.exit(1);
				}
				
				fs = FileSystem.get(job);
				calculator = new KernelCalculator();
				if(debug)	printMemory();
				
				
				
				
			
				
			} catch (IOException e) {
				e.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
			}
	    }
		
		
		public void loadAMatrix(Path filepath) throws IOException {
			calculator.inmatrix = null;
			System.gc();		//control the memory while loading the block
			
			String filename = filepath.getName();
			int lineno = Integer.parseInt(filename.substring(filename.lastIndexOf("-")+1, filename.length()));
			calculator.inmatrix = new KernelRow[lineno];
			DataInputStream br = new DataInputStream(new BufferedInputStream(fs.open(filepath),1024*8*16));
			
			for(int i = 0 ; i < lineno ; i ++) {
				int thislineno = br.readInt();
				int arraysize = br.readInt();
				KernelRow thisrow = new KernelRow(thislineno, arraysize);
				for(int j = 0 ; j < arraysize ; j ++) {
					thisrow.indexes[j] = br.readShort();
					thisrow.values[j] = br.readFloat();
				}
				calculator.inmatrix[i] = thisrow;

					
			}
			logger.info("loaded "+filename+":"+lineno);
			if(debug)	printMemory();
			br.close();
			
		}
		
	
		
		
		
		@Override
		public void map(IntWritable key, KernelRowArrayWritable values,
				OutputCollector<IntWritable, Text> output, Reporter reporter)
				throws IOException {
			logger.info("--------------------------------:entered map loop-----------------------------------\r\n");
			printMemory();
			Writable[] input = values.get();
			if(input == null || input.length == 0) {
				output.collect(key, null);
				return;
			}
			
			float[][] small = new float[input.length][];
			if(debug)	{
				logger.info("before create big matrix");
				printMemory();
			}

			float[][] big = new float[input.length][row_a];
			if(debug)	{
				logger.info("after create big matrix");
				printMemory();
			}

			
			KernelRow[] inputB = new KernelRow[input.length];
			for(int i = 0 ; i < inputB.length ; i++) {
				KernelRowWritable thisWritable = (KernelRowWritable)input[i];
				KernelRow row = thisWritable.toKernelRow();
				inputB[i] = row;
				
			}
			
			int a_chunk_size = 0;
			logger.info("calculating the kernel\r\n");
			logger.info("input:" + input.length);
			
			for(int p = 0  ; p < pathes.length ; p++) {
				loadAMatrix(pathes[p]);		//load A block
				for(int i = 0 ; i < input.length ; i++) {
					if(kernel_type.equals("chi2"))
						small[i] = calculator.chi2LeiWithZeros(inputB[i]);			//calculate the kernel
					else if(kernel_type.equals("rbf"))
						small[i] = calculator.rbf(inputB[i]);			//calculate the kernel
						
				}
				if(p == 0) a_chunk_size = small[0].length;			//record the chunk size of a by recording the line number of the first file
				reporter.setStatus("<br>\n I am still alive. Don't kill me...");
				if(debug)	logger.info("just calculated[" + calculator.inmatrix.length +"*" + inputB.length+"]");
				if(debug)	printMemory();
				appendToBig(big, small, p*a_chunk_size);
			}
			
			logger.info(":calculation done\r\n");
			printMemory();
			small = null;
			//check point test!
			
			
			//write out the kernel matrix chunk into a binary file
			Path outFile = new Path(buffer + File.separator + "kernelmatrix-c"+key.toString()+"-r" + input.length);
			writeMatrixChunk2Binary(outFile, big);
			logger.info("writed out" + outFile.getName());
			printMemory();
			
			output.collect(key, new Text("ok"));
			big = null;
			System.gc();


		}
		
		public void printMemory() {
			logger.info(":max-memory:"+(Runtime.getRuntime().maxMemory()/1024/1024)+
					"m:free-memory:"+(Runtime.getRuntime().freeMemory()/1024/1024)+
					"m:total:"+(Runtime.getRuntime().totalMemory()/1024/1024)+"m\r\n");
		}
		
		/**
		 * Append small kernel matrix (calculated from part of A matrix and B) into the big matrix (stroing the whole kernel distance between A and B)
		 * @param big the big matrix storing the kernel distance
		 * @param small the small matrix storing the kernel distance chunk of part A and B
		 * @param startIndex
		 */
		private void appendToBig(float[][] big, float[][] small, int startIndex) {
			for(int i = 0 ; i < big.length ; i++) {
				for(int j = 0 ; j < small[i].length ; j++) {
					big[i][startIndex+j] = small[i][j];		
				}
			}
		}
		
		/**
		 * decode the result from sortAPath(String a_matrix_dir) into a set of files
		 * @param in the output of sortAPath(String a_matrix_dir)
		 * @return a set of files
		 */
		private Path[] topathes(String in) {
			String[] temp = in.split("#");
			Path[] result = new Path[temp.length];
			for(int i = 0 ; i < temp.length ; i++) {
				result[i] = new Path(temp[i]);
			}
			
			return result;
		}
		
		protected void writeMatrixChunk2Binary(Path outfile, float[][] big) throws IOException {
			DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fs.create(outfile,(short)3)));  //replicate the file 5 times for robust
			for(int i = 0 ; i < big.length ; i++) {
				for(int j = 0 ; j < big[i].length ; j++) {
					out.writeFloat(big[i][j]);
				}
			}
			out.flush();
			out.close();
		}
	}
	
	/**
	 * dummy reducer
	 * @author lujiang
	 *
	 */
	public static class SVMHadoopReducer extends MapReduceBase implements
			Reducer<IntWritable, FloatMatrix, IntWritable, Text> {
		
		
		public void configure(JobConf job) {
			
	    }
		
		@Override
		public void reduce(IntWritable key, Iterator<FloatMatrix> values,OutputCollector<IntWritable, Text> output, Reporter reporter) throws IOException {
				output.collect(key, new Text("ok"));
		}
	}
	
	
	/**
	 * Convert all the files in a dir into a string and sort their names
	 * @param a_matrix_dir a dir
	 * @return a string 
	 * @throws Exception
	 */
	private static String sortAPath(FileSystem fs, String a_feature_binary_file) throws Exception {
		FileStatus[] status = fs.listStatus(new Path(a_feature_binary_file));
		ArrayList<String> a_feature_binary_name = new ArrayList<String>();
		for (int i = 0; i < status.length; i++) {
			Path thispath = status[i].getPath();
			if (thispath.getName().indexOf("inpart") != -1) {
				a_feature_binary_name.add(thispath.toString());
			}
		}
		ArrayList<Integer> a_feature_ids = new ArrayList<Integer>();

		// extract small file id from the a_feature_binary file name
		for (int i = 0; i < a_feature_binary_name.size(); i++) {
			String tfilename = a_feature_binary_name.get(i);
			int id = -1;
			try{
				id = Integer.parseInt(tfilename.substring(
						tfilename.indexOf("inpart") + "inpart".length(),
						tfilename.lastIndexOf("-")));
			} catch(NumberFormatException e) {
				throw new Exception("The input A feature file name is bad formateed:" + tfilename +"\r\n" + e.toString());
			}
			a_feature_ids.add(id);

		}

		// sort the a_feature_binary according to the id! index starting from 0
		String result = "";
		for (int i = 0; i < a_feature_binary_name.size(); i++) {
			boolean found = false;
			int j = 0;
			for (; j < a_feature_ids.size(); j++) {
				if (i == a_feature_ids.get(j)) {
					found = true;
					break;
				}
			}
			if (found) {
				result += a_feature_binary_name.get(j) + "#";
			} else {
				throw new Exception(
						"the input A feature is not consecutive! Missing:" + i);
			}
		}
		result = result.substring(0, result.length() - 1);
		return result;
	}
	
	
	
	public static void main(String[] args) throws Exception {
		
		String a_feature_binary_file = args[0];			//small a matrix dir
		String b_feature_sequence_file = args[1];		//sequence file of b
		
		JobConf conf = new JobConf(PredictionMapper.class);
		conf.set("row_a", args[2]);						//number of row a		236697
		conf.set("kernelmatrix_buffer", args[3]);		//buffer that contains the kernel matrix chunk
		
		FileSystem fs = FileSystem.get(conf);
		String in_a_pathes = sortAPath(fs,a_feature_binary_file);						//the pathes of all small a matrix
		
		//System.out.println(in_a_pathes);
		
        conf.set("in_a_pathes", in_a_pathes);
    	conf.set("mapred.child.java.opts", "-Xmx1200m");		//cannot be too large <2000m
		
		conf.set("mapred.cluster.map.memory.mb","2000");
		conf.set("mapred.cluster.reduce.memory.mb","2000");
		
		conf.set("mapred.job.map.memory.mb","2000");
		conf.set("mapred.job.reduce.memory.mb","2000");
		conf.set("mapred.tasktracker.map.tasks.maximum","1");
		conf.set("mapred.map.max.attempts","8");
		conf.set("mapred.reduce.max.attempts","8");
			
		conf.setJobName("cascade-svm-kernel-computer");
			
			
		//out key and value for mapper
		conf.setMapOutputKeyClass(IntWritable.class);
		conf.setMapOutputValueClass(Text.class);
		//out key and value for reducer
		conf.setOutputKeyClass(IntWritable.class);
		conf.setOutputValueClass(Text.class);
			
		conf.setMapperClass(SVMHadoopMapper.class);
		conf.setReducerClass(SVMHadoopReducer.class);
		conf.setNumMapTasks(500);
		conf.setNumReduceTasks(1);
			
		conf.setInputFormat(SequenceFileInputFormat.class);
		conf.setOutputFormat(TextOutputFormat.class);
			
		//String thispathname = pathes.get(i).toString();
		//int iteration_num = Integer.parseInt(thispathname.substring(thispathname.indexOf("inpart")+"inpart".length(), thispathname.lastIndexOf("-")));
		FileInputFormat.setInputPaths(conf, new Path(b_feature_sequence_file));
		FileOutputFormat.setOutputPath(conf, new Path(args[4]));
		if(args.length ==6) {
			conf.set("kernel_type", args[5].toLowerCase());
		} else {
			conf.set("kernel_type", "chi2");
		}
		JobClient.runJob(conf);
		


	}
	
	
}
