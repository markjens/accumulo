/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.server.conf;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.endsWith;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.DefaultConfiguration;
import org.apache.accumulo.core.conf.SiteConfiguration;
import org.apache.accumulo.core.data.NamespaceId;
import org.apache.accumulo.fate.zookeeper.ZooCache;
import org.apache.accumulo.fate.zookeeper.ZooCacheFactory;
import org.apache.accumulo.server.MockServerContext;
import org.apache.accumulo.server.ServerContext;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class ServerConfigurationFactoryTest {
  private static final String ZK_HOST = "localhost";
  private static final int ZK_TIMEOUT = 120000;
  private static final String IID = "iid";

  // use the same mock ZooCacheFactory and ZooCache for all tests
  private static ZooCacheFactory zcf;
  private static ZooCache zc;
  private static SiteConfiguration siteConfig = SiteConfiguration.auto();

  @BeforeClass
  public static void setUpClass() {
    zcf = createMock(ZooCacheFactory.class);
    zc = createMock(ZooCache.class);
    expect(zcf.getZooCache(eq(ZK_HOST), eq(ZK_TIMEOUT), EasyMock.anyObject())).andReturn(zc);
    expectLastCall().anyTimes();
    expect(zcf.getZooCache(ZK_HOST, ZK_TIMEOUT)).andReturn(zc);
    expectLastCall().anyTimes();
    replay(zcf);

    expect(zc.getChildren(anyObject(String.class))).andReturn(null);
    expectLastCall().anyTimes();
    // CheckServerConfig looks at timeout
    expect(zc.get(endsWith("timeout"))).andReturn(("" + ZK_TIMEOUT + "ms").getBytes(UTF_8));
    replay(zc);
  }

  private ServerContext context;
  private ServerConfigurationFactory scf;

  @Before
  public void setUp() {
    context = MockServerContext.getWithZK(IID, ZK_HOST, ZK_TIMEOUT);
  }

  @After
  public void tearDown() {
    ServerConfigurationFactory.clearCachedConfigurations();
  }

  private void ready() {
    replay(context);
    scf = new ServerConfigurationFactory(context, siteConfig);
    scf.setZooCacheFactory(zcf);
  }

  @Test
  public void testGetDefaultConfiguration() {
    ready();
    DefaultConfiguration c = scf.getDefaultConfiguration();
    assertNotNull(c);
  }

  @Test
  public void testGetSiteConfiguration() {
    ready();
    var c = scf.getSiteConfiguration();
    assertNotNull(c);
  }

  @Test
  public void testGetConfiguration() {
    ready();
    AccumuloConfiguration c = scf.getSystemConfiguration();
    assertNotNull(c);
  }

  private static final NamespaceId NSID = NamespaceId.of("NAMESPACE");

  @Test
  public void testGetNamespaceConfiguration() {
    ready();
    NamespaceConfiguration c = scf.getNamespaceConfiguration(NSID);
    assertEquals(NSID, c.getNamespaceId());
    assertSame(c, scf.getNamespaceConfiguration(NSID));
  }

}
