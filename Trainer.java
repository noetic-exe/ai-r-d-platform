package com.aird;

import java.util.*;
import java.util.function.Consumer;

/** Small, bounded CPU estimators. No uploaded executable code or native runtime. */
public class Trainer {
    public record Result(Map<String,Object> metrics,Map<String,Object> artifact) {}
    public record Node(int feature,double threshold,int label,Node left,Node right) {}
    record Feature(int column,String name,boolean numeric,double mean,double scale,List<String> categories) {}
    static double numeric(String s) {try {double d=Double.parseDouble(s);return Double.isFinite(d)?d:Double.NaN;}catch(Exception e){return Double.NaN;}}
    static double dot(double[] w,double[] x) {double out=w[0];for(int j=0;j<x.length;j++)out+=w[j+1]*x[j];return out;}
    public Result train(FileStorage.Table table,String target,String model,Map<String,Object> params,int seed,double fraction,Consumer<String> stage,Runnable check) {
        int tc=table.headers().indexOf(target);Problem.require(tc>=0,422,"Target column does not exist.");
        Problem.require(table.rows().size()>=10,422,"Training needs at least 10 rows.");
        for(var row:table.rows()) Problem.require(!row[tc].isBlank(),422,"Target column contains missing values.");
        boolean regression=model.equals("linear_regression");Random random=new Random(seed);
        List<String> labels=regression?List.of():table.rows().stream().map(r->r[tc]).distinct().sorted().toList();
        Problem.require(regression || labels.size()>=2 && labels.size()<=20,422,"Classification requires 2–20 target classes.");
        var trainIndices=new ArrayList<Integer>();var testIndices=new ArrayList<Integer>();
        if(regression) {
            var all=new ArrayList<Integer>();for(int i=0;i<table.rows().size();i++){Problem.require(Double.isFinite(numeric(table.rows().get(i)[tc])) && Math.abs(numeric(table.rows().get(i)[tc]))<=1e12,422,"Regression target must be finite numeric values.");all.add(i);}
            Collections.shuffle(all,random);int count=Math.max(2,(int)Math.round(all.size()*fraction));testIndices.addAll(all.subList(0,count));trainIndices.addAll(all.subList(count,all.size()));
        } else for(String label:labels) {
            var ids=new ArrayList<Integer>();for(int i=0;i<table.rows().size();i++)if(table.rows().get(i)[tc].equals(label))ids.add(i);
            Problem.require(ids.size()>=3,422,"Each target class needs at least 3 rows for a stratified split.");
            Collections.shuffle(ids,random);int count=Math.max(1,Math.min(ids.size()-2,(int)Math.round(ids.size()*fraction)));
            testIndices.addAll(ids.subList(0,count));trainIndices.addAll(ids.subList(count,ids.size()));
        }
        Collections.shuffle(trainIndices,random);Collections.shuffle(testIndices,random);
        var features=new ArrayList<Feature>();int width=0;
        for(int c=0;c<table.headers().size();c++)if(c!=tc) {
            boolean isNumeric=true;double sum=0;int count=0;var categories=new TreeSet<String>();
            for(int i:trainIndices) {String value=table.rows().get(i)[c];categories.add(value.isBlank()?"__MISSING__":value);if(!value.isBlank()){double d=numeric(value);if(!Double.isFinite(d))isNumeric=false;else{Problem.require(Math.abs(d)<=1e12,422,"Numeric feature magnitude exceeds the supported limit of 1e12.");sum+=d;count++;}}}
            if(isNumeric) {
                double mean=count==0?0:sum/count,variance=0;
                for(int i:trainIndices){double v=numeric(table.rows().get(i)[c]);if(Double.isFinite(v))variance+=(v-mean)*(v-mean);}
                double scale=Math.sqrt(variance/Math.max(1,count));features.add(new Feature(c,table.headers().get(c),true,mean,scale<1e-9?1:scale,List.of()));width++;
            } else {
                Problem.require(categories.size()<=32,422,"Categorical feature '"+table.headers().get(c)+"' exceeds 32 categories. Remove identifiers or encode them before upload.");
                features.add(new Feature(c,table.headers().get(c),false,0,1,List.copyOf(categories)));width+=categories.size();
            }
        }
        Problem.require(width>0 && width<=256,422,"Encoded dataset must have 1–256 features.");
        double[][] x=encode(table,trainIndices,features,width),xt=encode(table,testIndices,features,width);
        double[] y=new double[x.length],yt=new double[xt.length];
        for(int i=0;i<y.length;i++) y[i]=regression?numeric(table.rows().get(trainIndices.get(i))[tc]):labels.indexOf(table.rows().get(trainIndices.get(i))[tc]);
        for(int i=0;i<yt.length;i++) yt[i]=regression?numeric(table.rows().get(testIndices.get(i))[tc]):labels.indexOf(table.rows().get(testIndices.get(i))[tc]);
        stage.accept("training");check.run();
        var artifact=new LinkedHashMap<String,Object>();artifact.put("format","aird-java-model-v1");artifact.put("model",model);artifact.put("target",target);artifact.put("features",features);artifact.put("classes",labels);artifact.put("parameters",params);artifact.put("seed",seed);artifact.put("test_fraction",fraction);artifact.put("split","stratified for classification; seeded shuffle for regression");artifact.put("train_rows",x.length);artifact.put("test_rows",xt.length);artifact.put("java_version",System.getProperty("java.version"));artifact.put("implementation_version","1.0.0");artifact.put("code_commit",System.getenv().getOrDefault("CODE_COMMIT","local-source"));artifact.put("preprocessing","Training-only mean imputation / standardization, one-hot categorical encoding. Unseen categories map to zero.");
        double[] predictions=new double[xt.length];
        if(model.equals("random_forest")) {
            int trees=param(params,"n_estimators",32),depth=param(params,"max_depth",8);var forest=new ArrayList<Node>();
            for(int t=0;t<trees;t++) {
                check.run();int[] sample=new int[Math.min(x.length,4000)];for(int i=0;i<sample.length;i++)sample[i]=random.nextInt(x.length);
                forest.add(tree(x,y,sample,0,depth,labels.size(),random,check));
            }
            for(int i=0;i<xt.length;i++){int[] votes=new int[labels.size()];for(var tree:forest)votes[predict(tree,xt[i])]++;predictions[i]=argmax(votes);}
            artifact.put("trees",forest);artifact.put("bootstrap_sample_cap",4000);
        } else if(model.equals("baseline")) {
            int[] counts=new int[labels.size()];for(double label:y)counts[(int)label]++;int majority=argmax(counts);Arrays.fill(predictions,majority);artifact.put("majority_class",majority);
        } else {
            int k=regression?1:labels.size(),epochs=param(params,"epochs",100);double[][] weights=new double[k][width+1];double mean=regression?Arrays.stream(y).average().orElse(0):0,scale=1;
            if(regression){double var=0;for(double v:y)var+=(v-mean)*(v-mean);scale=Math.sqrt(var/y.length);if(scale<1e-9)scale=1;}
            for(int epoch=0;epoch<epochs;epoch++) {
                check.run();double rate=0.03/Math.sqrt(1+epoch*0.15);int samples=Math.min(x.length,4096);
                for(int step=0;step<samples;step++) {
                    int i=random.nextInt(x.length);double[] errors=new double[k];
                    if(regression)errors[0]=Math.clamp(dot(weights[0],x[i])-(y[i]-mean)/scale,-10,10);
                    else {double max=-Double.MAX_VALUE,total=0;for(int c=0;c<k;c++){errors[c]=dot(weights[c],x[i]);max=Math.max(max,errors[c]);}for(int c=0;c<k;c++){errors[c]=Math.exp(errors[c]-max);total+=errors[c];}for(int c=0;c<k;c++)errors[c]=errors[c]/total-(c==(int)y[i]?1:0);}
                    for(int c=0;c<k;c++){weights[c][0]-=rate*errors[c];for(int j=0;j<width;j++)weights[c][j+1]-=rate*(errors[c]*Math.clamp(x[i][j],-10,10)+0.0001*weights[c][j+1]);}
                }
            }
            for(int i=0;i<xt.length;i++) {if(regression)predictions[i]=dot(weights[0],xt[i])*scale+mean;else{double best=-Double.MAX_VALUE;for(int c=0;c<k;c++){double score=dot(weights[c],xt[i]);if(score>best){best=score;predictions[i]=c;}}}}
            artifact.put("weights",weights);artifact.put("target_mean",mean);artifact.put("target_scale",scale);artifact.put("optimizer","seeded SGD; up to 4096 sampled rows per epoch; L2=0.0001");
        }
        stage.accept("evaluating");check.run();
        var metrics=new LinkedHashMap<String,Object>();metrics.put("split","test");metrics.put("train_rows",x.length);metrics.put("test_rows",xt.length);
        if(regression) {
            double mae=0,sse=0,sst=0,mean=Arrays.stream(yt).average().orElse(0);
            for(int i=0;i<yt.length;i++){double error=yt[i]-predictions[i];mae+=Math.abs(error);sse+=error*error;sst+=(yt[i]-mean)*(yt[i]-mean);}
            metrics.put("mae",mae/yt.length);metrics.put("rmse",Math.sqrt(sse/yt.length));metrics.put("r2",sst<1e-12?0:1-sse/sst);
        } else {
            int[][] matrix=new int[labels.size()][labels.size()];int correct=0;for(int i=0;i<yt.length;i++){matrix[(int)yt[i]][(int)predictions[i]]++;if(yt[i]==predictions[i])correct++;}
            double precision=0,recall=0,f1=0;
            for(int c=0;c<labels.size();c++){int tp=matrix[c][c],actual=0,predicted=0;for(int j=0;j<labels.size();j++){actual+=matrix[c][j];predicted+=matrix[j][c];}double p=predicted==0?0:(double)tp/predicted,r=actual==0?0:(double)tp/actual;precision+=p;recall+=r;f1+=p+r==0?0:2*p*r/(p+r);}
            metrics.put("accuracy",(double)correct/yt.length);metrics.put("precision",precision/labels.size());metrics.put("recall",recall/labels.size());metrics.put("f1",f1/labels.size());metrics.put("averaging","macro");metrics.put("classes",labels);metrics.put("confusion_matrix",matrix);
        }
        artifact.put("metrics",metrics);return new Result(metrics,artifact);
    }
    static int param(Map<String,Object> params,String key,int fallback){return params.get(key) instanceof Number n?n.intValue():fallback;}
    static double[][] encode(FileStorage.Table t,List<Integer> indices,List<Feature> features,int width) {
        var out=new double[indices.size()][width];
        for(int i=0;i<out.length;i++){int pos=0;for(var f:features){String value=t.rows().get(indices.get(i))[f.column()];if(f.numeric()){double d=numeric(value);out[i][pos++]=Math.clamp(((Double.isFinite(d)?d:f.mean())-f.mean())/f.scale(),-10,10);}else{int at=f.categories().indexOf(value.isBlank()?"__MISSING__":value);if(at>=0)out[i][pos+at]=1;pos+=f.categories().size();}}}return out;
    }
    static int argmax(int[] a){int best=0;for(int i=1;i<a.length;i++)if(a[i]>a[best])best=i;return best;}
    static double impurity(int[] counts,int n){if(n==0)return 0;double sum=1;for(int c:counts)sum-=(double)c*c/n/n;return sum;}
    static Node tree(double[][] x,double[] y,int[] ids,int depth,int maxDepth,int k,Random random,Runnable check) {
        check.run();int[] counts=new int[k];for(int id:ids)counts[(int)y[id]]++;int label=argmax(counts);
        if(depth>=maxDepth || ids.length<6 || counts[label]==ids.length)return new Node(-1,0,label,null,null);
        double best=Double.MAX_VALUE,threshold=0;int feature=-1;
        var candidates=new HashSet<Integer>();while(candidates.size()<Math.max(1,(int)Math.sqrt(x[0].length)))candidates.add(random.nextInt(x[0].length));
        for(int c:candidates)for(int trial=0;trial<6;trial++) {
            double split=x[ids[random.nextInt(ids.length)]][c];int[] left=new int[k],right=counts.clone();int nl=0;
            for(int id:ids)if(x[id][c]<=split){left[(int)y[id]]++;right[(int)y[id]]--;nl++;}
            if(nl<2 || ids.length-nl<2)continue;
            double score=(nl*impurity(left,nl)+(ids.length-nl)*impurity(right,ids.length-nl))/ids.length;
            if(score<best){best=score;threshold=split;feature=c;}
        }
        if(feature<0)return new Node(-1,0,label,null,null);
        int size=0;for(int id:ids)if(x[id][feature]<=threshold)size++;int[] l=new int[size],r=new int[ids.length-size];int li=0,ri=0;for(int id:ids)if(x[id][feature]<=threshold)l[li++]=id;else r[ri++]=id;
        return new Node(feature,threshold,label,tree(x,y,l,depth+1,maxDepth,k,random,check),tree(x,y,r,depth+1,maxDepth,k,random,check));
    }
    static int predict(Node n,double[] x){while(n.feature()>=0)n=x[n.feature()]<=n.threshold()?n.left():n.right();return n.label();}
}
