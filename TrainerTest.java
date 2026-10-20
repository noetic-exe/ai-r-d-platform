package com.aird;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TrainerTest {
 FileStorage.Table fixture(){var rows=new ArrayList<String[]>();for(int i=0;i<160;i++)rows.add(new String[]{""+(i%80),i%80<40?"blue":"red",i%80<40?"no":"yes"});return new FileStorage.Table(List.of("x","color","target"),rows);}
 @Test void classificationIsRealAndRepeatable(){for(String model:List.of("logistic_regression","random_forest","baseline")){var t=new Trainer();var r=t.train(fixture(),"target",model,Map.of("n_estimators",10),42,.2,s->{},()->{});var again=t.train(fixture(),"target",model,Map.of("n_estimators",10),42,.2,s->{},()->{});assertEquals(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(r.metrics()),tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(again.metrics()));assertTrue(((Number)r.metrics().get("accuracy")).doubleValue()>=(model.equals("baseline")?.5:.9));assertNotNull(r.artifact().get("features"));}}
 @Test void regressionFitsKnownRelationship(){var rows=new ArrayList<String[]>();for(int i=0;i<100;i++)rows.add(new String[]{""+i,""+(2*i+7)});var r=new Trainer().train(new FileStorage.Table(List.of("x","y"),rows),"y","linear_regression",Map.of(),7,.2,s->{},()->{});assertTrue(((Number)r.metrics().get("r2")).doubleValue()>.99);}
 @Test void invalidTargetFails(){assertThrows(Problem.class,()->new Trainer().train(fixture(),"missing","baseline",Map.of(),42,.2,s->{},()->{}));}
 @Test void cancellationIsCooperative(){assertThrows(java.util.concurrent.CancellationException.class,()->new Trainer().train(fixture(),"target","random_forest",Map.of(),42,.2,s->{},()->{throw new java.util.concurrent.CancellationException();}));}
 @Test void unknownCategoriesAreEncodedWithoutFittingTestData(){var t=new FileStorage.Table(List.of("x","category"),List.<String[]>of(new String[]{"9999","unseen"}));var fs=List.of(new Trainer.Feature(0,"x",true,2,1,List.of()),new Trainer.Feature(1,"category",false,0,1,List.of("a")));var x=Trainer.encode(t,List.of(0),fs,2);assertEquals(10,x[0][0]);assertEquals(0,x[0][1]);}
}
