/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.test;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.Map.Entry;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.TablePermission;
import org.apache.accumulo.minicluster.impl.MiniAccumuloConfigImpl;
import org.apache.accumulo.server.init.Initialize;
import org.apache.accumulo.server.util.Admin;
import org.apache.accumulo.server.util.RandomizeVolumes;
import org.apache.accumulo.test.functional.ConfigurableMacIT;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RawLocalFileSystem;
import org.apache.hadoop.io.Text;
import org.junit.Test;

import static org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.ServerColumnFamily.DIRECTORY_COLUMN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// ACCUMULO-3263
public class RewriteTabletDirectoriesIT extends ConfigurableMacIT {
  
  private Path v1, v2;

  @Override
  public void configure(MiniAccumuloConfigImpl cfg, Configuration hadoopCoreSite) {
    File baseDir = cfg.getDir();
    File volDirBase = new File(baseDir, "volumes");
    File v1f = new File(volDirBase, "v1");
    File v2f = new File(volDirBase, "v2");
    v1f.mkdir();
    v2f.mkdir();
    v1 = new Path("file://" + v1f.getAbsolutePath());
    v2 = new Path("file://" + v2f.getAbsolutePath());

    // Run MAC on two locations in the local file system
    cfg.setProperty(Property.INSTANCE_VOLUMES, v1.toString());
    hadoopCoreSite.set("fs.file.impl", RawLocalFileSystem.class.getName());
    super.configure(cfg, hadoopCoreSite);
  }

  @Test(timeout = 4 * 60 * 1000)
  public void test() throws Exception {
    Connector c = getConnector();
    c.securityOperations().grantTablePermission(c.whoami(), MetadataTable.NAME, TablePermission.WRITE);
    final String tableName = getUniqueNames(1)[0];
    c.tableOperations().create(tableName);
    BatchWriter bw = c.createBatchWriter(tableName, null);
    final SortedSet<Text> splits = new TreeSet<Text>();
    for (String split : "a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u,v,w,x,y,z".split(",")) {
      splits.add(new Text(split));
      Mutation m = new Mutation(new Text(split));
      m.put(new byte[]{}, new byte[]{}, new byte[]{});
      bw.addMutation(m);
    }
    bw.close();
    c.tableOperations().addSplits(tableName, splits);
    
    BatchScanner scanner = c.createBatchScanner(MetadataTable.NAME, Authorizations.EMPTY, 1);
    DIRECTORY_COLUMN.fetch(scanner);
    String tableId = c.tableOperations().tableIdMap().get(tableName);
    scanner.setRanges(Collections.singletonList(TabletsSection.getRange(tableId)));
    // verify the directory entries are all on v1, make a few entries relative
    bw = c.createBatchWriter(MetadataTable.NAME, null);
    int count = 0;
    for (Entry<Key,Value> entry : scanner) {
      assertTrue(entry.getValue().toString().contains(v1.toString()));
      count++;
      if (count % 2 == 0) {
        String parts[] = entry.getValue().toString().split("/");
        Key key = entry.getKey();
        Mutation m = new Mutation(key.getRow());
        m.put(key.getColumnFamily(), key.getColumnQualifier(), new Value((Path.SEPARATOR + parts[parts.length - 1]).getBytes()));
        bw.addMutation(m);
      }
    }
    bw.close();
    assertEquals(splits.size() + 1, count);
    
    // This should fail: only one volume
    assertEquals(1, cluster.exec(RandomizeVolumes.class, "-z", cluster.getZooKeepers(), "-i", c.getInstance().getInstanceName(), "-t", tableName).waitFor());
    
    cluster.stop();

    // add the 2nd volume
    Configuration conf = new Configuration(false);
    conf.addResource(new Path(cluster.getConfig().getConfDir().toURI().toString(), "accumulo-site.xml"));
    conf.set(Property.INSTANCE_VOLUMES.getKey(), v1.toString() + "," + v2.toString());
    BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(new File(cluster.getConfig().getConfDir(), "accumulo-site.xml")));
    conf.writeXml(fos);
    fos.close();
    
    // initialize volume
    assertEquals(0, cluster.exec(Initialize.class, "--add-volumes").waitFor());
    cluster.start();
    c = getConnector();

    // change the directory entries
    assertEquals(0, cluster.exec(Admin.class, "randomizeVolumes", "-t", tableName).waitFor());

    // verify a more equal sharing
    int v1Count = 0, v2Count = 0;
    for (Entry<Key,Value> entry : scanner) {
      if (entry.getValue().toString().contains(v1.toString())) {
        v1Count++;
      }
      if (entry.getValue().toString().contains(v2.toString())) {
        v2Count++;
      }
    }
    assertEquals(splits.size() + 1, v1Count + v2Count);
    assertTrue(Math.abs(v1Count - v2Count) < 10);
    // verify we can read the old data
    count = 0;
    for (Entry<Key,Value> entry : c.createScanner(tableName, Authorizations.EMPTY)) {
      assertTrue(splits.contains(entry.getKey().getRow()));
      count++;
    }
    assertEquals(splits.size(), count);
  }
}
