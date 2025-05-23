package com.linkedin.darkcluster;

import com.linkedin.d2.balancer.servers.ZooKeeperAnnouncer;
import com.linkedin.darkcluster.api.DarkGateKeeper;
import com.linkedin.darkcluster.api.DarkRequestHeaderGenerator;
import java.net.URI;

import com.linkedin.d2.DarkClusterConfig;
import com.linkedin.d2.balancer.Facilities;
import com.linkedin.darkcluster.api.DarkClusterManager;
import com.linkedin.darkcluster.api.DarkClusterStrategy;
import com.linkedin.darkcluster.api.DarkClusterStrategyFactory;
import com.linkedin.darkcluster.api.NoOpDarkClusterStrategy;
import com.linkedin.darkcluster.impl.DarkClusterManagerImpl;
import com.linkedin.r2.message.RequestContext;
import com.linkedin.r2.message.rest.RestRequest;
import com.linkedin.r2.message.rest.RestRequestBuilder;

import static com.linkedin.darkcluster.DarkClusterTestUtil.createRelativeTrafficMultiplierConfig;
import static com.linkedin.darkcluster.TestDarkClusterStrategyFactory.DARK_CLUSTER_NAME;
import static com.linkedin.darkcluster.TestDarkClusterStrategyFactory.DARK_CLUSTER_NAME2;
import static com.linkedin.darkcluster.TestDarkClusterStrategyFactory.SOURCE_CLUSTER_NAME;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.Optional;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class TestDarkClusterManager
{
  private static final String METHOD_SAFE = "GET";
  private static final String METHOD_UNSAFE = "POST";

  @DataProvider
  public Object[][] provideKeys()
  {
    DarkGateKeeper darkGateKeeper = new DarkGateKeeper() {
      @Override
      public boolean shouldDispatchToDark(RestRequest request, RequestContext requestContext, String darkClusterName) {
        return false;
      }
    };

    return new Object[][] {
      // whitelist, blacklist, httpMethod, darkGateKeeper, expected white count, expected black count
      {null, null, METHOD_SAFE, null, 1, 1},
      {null, null, METHOD_UNSAFE, null, 0, 0},
      {".*white.*", null, METHOD_SAFE, null, 1, 1},
      {".*white.*", null, METHOD_UNSAFE, null, 1, 0},
      {".*white.*", ".*black.*", METHOD_SAFE, null, 1, 0},
      {".*white.*", ".*black.*", METHOD_UNSAFE, null, 1, 0},
      {null, ".*black.*", METHOD_SAFE, null, 1, 0},
      {null, ".*black.*", METHOD_UNSAFE, null, 0, 0},
      {null, null, METHOD_SAFE, darkGateKeeper, 0, 0},
      {null, null, METHOD_UNSAFE, darkGateKeeper, 0, 0},
      {".*white.*", null, METHOD_SAFE, darkGateKeeper, 0, 0},
      {".*white.*", null, METHOD_UNSAFE, darkGateKeeper, 0, 0},
      {".*white.*", ".*black.*", METHOD_SAFE, darkGateKeeper, 0, 0},
      {".*white.*", ".*black.*", METHOD_UNSAFE, darkGateKeeper, 0, 0},
      {null, ".*black.*", METHOD_SAFE, darkGateKeeper, 0, 0},
      {null, ".*black.*", METHOD_UNSAFE, darkGateKeeper, 0, 0}
    };
  }

  @Test(dataProvider = "provideKeys")
  public void testBasic(String whitelist, String blacklist, String httpMethod, DarkGateKeeper darkGateKeeper,
      int expectedWhiteCount, int expectedBlackCount)
  {
    MockClusterInfoProvider clusterInfoProvider = new MockClusterInfoProvider();
    Facilities facilities = new MockFacilities(clusterInfoProvider);
    MockStrategyFactory strategyFactory = new MockStrategyFactory();
    DarkClusterManager darkClusterManager = new DarkClusterManagerImpl(SOURCE_CLUSTER_NAME,
                                                                       facilities,
                                                                       strategyFactory,
                                                                       whitelist,
                                                                       blacklist,
                                                                       new DoNothingNotifier(),
                                                                       darkGateKeeper);

    strategyFactory.start();

    // This configuration will choose the RelativeTrafficMultiplierDarkClusterStrategy
    DarkClusterConfig darkClusterConfig = createRelativeTrafficMultiplierConfig(1.0f);
    clusterInfoProvider.addDarkClusterConfig(SOURCE_CLUSTER_NAME, DARK_CLUSTER_NAME, darkClusterConfig);

    RestRequest restRequest1 = new RestRequestBuilder(URI.create("/white")).setMethod(httpMethod).build();
    boolean whiteStatus = darkClusterManager.handleDarkRequest(restRequest1, new RequestContext());
    RestRequest restRequest2 = new RestRequestBuilder(URI.create("/black")).setMethod(httpMethod).build();
    boolean blackStatus = darkClusterManager.handleDarkRequest(restRequest2, new RequestContext());

    Assert.assertEquals(whiteStatus, expectedWhiteCount > 0, "white uri requests not as expected");
    Assert.assertEquals(blackStatus, expectedBlackCount > 0, "black uri requests not as expected");
    Assert.assertEquals(strategyFactory.strategyGetOrCreateCount, expectedWhiteCount + expectedBlackCount,
                        "unexpected strategy GetOrCreateCount");
  }

  @Test
  public void testDarkGateKeeper()
  {
    DarkGateKeeper darkGateKeeper = new DarkGateKeeper() {
      @Override
      public boolean shouldDispatchToDark(RestRequest request, RequestContext requestContext, String darkClusterName) {
        return darkClusterName.equals(DARK_CLUSTER_NAME);
      }
    };

    MockClusterInfoProvider clusterInfoProvider = new MockClusterInfoProvider();
    Facilities facilities = new MockFacilities(clusterInfoProvider);
    MockStrategyFactory strategyFactory = new MockStrategyFactory();
    DarkClusterManager darkClusterManager = new DarkClusterManagerImpl(SOURCE_CLUSTER_NAME,
        facilities,
        strategyFactory,
        null,
        null,
        new DoNothingNotifier(),
        darkGateKeeper);

    strategyFactory.start();

    // This configuration will choose the RelativeTrafficMultiplierDarkClusterStrategy
    DarkClusterConfig darkClusterConfig = createRelativeTrafficMultiplierConfig(1.0f);
    clusterInfoProvider.addDarkClusterConfig(SOURCE_CLUSTER_NAME, DARK_CLUSTER_NAME2, darkClusterConfig);

    RestRequest restRequest1 = new RestRequestBuilder(URI.create("/white")).setMethod(METHOD_SAFE).build();
    boolean whiteStatus = darkClusterManager.handleDarkRequest(restRequest1, new RequestContext());
    RestRequest restRequest2 = new RestRequestBuilder(URI.create("/black")).setMethod(METHOD_SAFE).build();
    boolean blackStatus = darkClusterManager.handleDarkRequest(restRequest2, new RequestContext());

    Assert.assertFalse(whiteStatus, "white uri requests not as expected");
    Assert.assertFalse(blackStatus, "black uri requests not as expected");
    Assert.assertEquals(strategyFactory.strategyGetOrCreateCount, 0,
        "unexpected strategy GetOrCreateCount");

    clusterInfoProvider.addDarkClusterConfig(SOURCE_CLUSTER_NAME, DARK_CLUSTER_NAME, darkClusterConfig);

    boolean whiteStatus1 = darkClusterManager.handleDarkRequest(restRequest1, new RequestContext());
    boolean blackStatus1 = darkClusterManager.handleDarkRequest(restRequest2, new RequestContext());

    Assert.assertTrue(whiteStatus1, "white uri requests not as expected");
    Assert.assertTrue(blackStatus1, "black uri requests not as expected");
    Assert.assertEquals(strategyFactory.strategyGetOrCreateCount, 2,
        "unexpected strategy GetOrCreateCount");
  }

  @Test
  public void testWithDarkHeaders() {
    MockClusterInfoProvider clusterInfoProvider = new MockClusterInfoProvider();
    Facilities facilities = new MockFacilities(clusterInfoProvider);
    // This configuration will choose the RelativeTrafficMultiplierDarkClusterStrategy
    DarkClusterConfig darkClusterConfig = createRelativeTrafficMultiplierConfig(1.0f);
    clusterInfoProvider.addDarkClusterConfig(SOURCE_CLUSTER_NAME, DARK_CLUSTER_NAME, darkClusterConfig);

    DarkClusterStrategyFactory mockStrategyFactory = mock(DarkClusterStrategyFactory.class);
    DarkClusterStrategy mockDarkStrategy = mock(DarkClusterStrategy.class);

    DarkRequestHeaderGenerator darkRequestHeaderGenerator = mock(DarkRequestHeaderGenerator.class);
    Mockito.when(mockStrategyFactory.get(DARK_CLUSTER_NAME)).thenReturn(mockDarkStrategy);
    Mockito.when(darkRequestHeaderGenerator.get(DARK_CLUSTER_NAME))
        .thenReturn(Optional.of(new DarkRequestHeaderGenerator.HeaderNameValuePair("header", "value")));

    RestRequest restRequest = new RestRequestBuilder(URI.create("/abc")).setMethod(METHOD_SAFE).build();
    RestRequest darkRequest = new RestRequestBuilder(URI.create("d2://" + DARK_CLUSTER_NAME + "/abc"))
        .setMethod(METHOD_SAFE)
        .setHeader("header", "value")
        .build();
    RequestContext requestContext = new RequestContext();
    Mockito.when(mockDarkStrategy.handleRequest(restRequest, darkRequest, new RequestContext(requestContext))).thenReturn(true);

    DarkClusterManager darkClusterManager = new DarkClusterManagerImpl(SOURCE_CLUSTER_NAME,
        facilities,
        mockStrategyFactory,
        null,
        null,
        new DoNothingNotifier(),
        null,
        Collections.singletonList(darkRequestHeaderGenerator));
    boolean status = darkClusterManager.handleDarkRequest(restRequest, requestContext);
    Assert.assertTrue(status);
  }

  @Test
  public void testDarkWarmup()
  {
    MockClusterInfoProvider clusterInfoProvider = new MockClusterInfoProvider();
    Facilities facilities = new MockFacilities(clusterInfoProvider);
    MockStrategyFactory strategyFactory = new MockStrategyFactory();
    DarkClusterManager darkClusterManager = new DarkClusterManagerImpl(SOURCE_CLUSTER_NAME,
        facilities,
        strategyFactory,
        null,
        null,
        new DoNothingNotifier(),
        null);
    ZooKeeperAnnouncer mockZkAnnouncer = mock(ZooKeeperAnnouncer.class);
    when(mockZkAnnouncer.isWarmingUp()).thenReturn(true);
    DarkClusterManager darkClusterManager2 = new DarkClusterManagerImpl(SOURCE_CLUSTER_NAME,
        facilities,
        strategyFactory,
        null,
        null,
        new DoNothingNotifier(),
        null,
        null,
        Collections.singletonList(mockZkAnnouncer));


    strategyFactory.start();

    // This configuration will choose the RelativeTrafficMultiplierDarkClusterStrategy
    DarkClusterConfig darkClusterConfig = createRelativeTrafficMultiplierConfig(1.0f);
    clusterInfoProvider.addDarkClusterConfig(SOURCE_CLUSTER_NAME, DARK_CLUSTER_NAME, darkClusterConfig);

    RestRequest restRequest = new RestRequestBuilder(URI.create("/test")).setMethod(METHOD_SAFE).build();
    Assert.assertTrue(darkClusterManager.handleDarkRequest(restRequest, new RequestContext()));
    Assert.assertFalse(darkClusterManager2.handleDarkRequest(restRequest, new RequestContext()));
    when(mockZkAnnouncer.isWarmingUp()).thenReturn(false);
    Assert.assertTrue(darkClusterManager2.handleDarkRequest(restRequest, new RequestContext()));
  }

  private static class MockStrategyFactory implements DarkClusterStrategyFactory
  {
    // Always return true from the strategy so that we can count reliably
    private static final DarkClusterStrategy NO_OP_STRATEGY = new NoOpDarkClusterStrategy(true);

    int strategyGetOrCreateCount;

    @Override
    public DarkClusterStrategy get(String darkClusterName)
    {
      strategyGetOrCreateCount++;
      return NO_OP_STRATEGY;
    }

    @Override
    public void start()
    {

    }

    @Override
    public void shutdown()
    {

    }
  }
}
