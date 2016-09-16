package io.vertx.ext.consul;

import com.pszymczyk.consul.ConsulProcess;
import com.pszymczyk.consul.ConsulStarterBuilder;
import com.pszymczyk.consul.LogLevel;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.test.core.VertxTestBase;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:ruslan.sennov@gmail.com">Ruslan Sennov</a>
 */
public class ConsulTestBase extends VertxTestBase {

    protected static final String MASTER_TOKEN = "topSecret";
    protected static final String DC = "test-dc";

    private static ConsulProcess consul;
    protected static String TEST_TOKEN;

    protected ConsulClient masterClient;
    protected ConsulClient testClient;

    protected JsonObject config(String token) {
        return new JsonObject()
                .put("acl_token", token)
                .put("dc", DC)
                .put("host", "localhost")
                .put("port", consul.getHttpPort());
    }

    @BeforeClass
    public static void startConsul() throws Exception {
        consul = ConsulStarterBuilder.consulStarter()
                .withLogLevel(LogLevel.ERR)
                .withCustomConfig(new JsonObject()
                        .put("datacenter", DC)
                        .put("acl_default_policy", "deny")
                        .put("acl_master_token", MASTER_TOKEN)
                        .put("acl_datacenter", DC).encode())
                .build()
                .start();
        HttpClient httpClient = Vertx.vertx().createHttpClient(new HttpClientOptions().setDefaultPort(consul.getHttpPort()));
        CountDownLatch latch = new CountDownLatch(1);
        httpClient.put("/v1/acl/create?token=" + MASTER_TOKEN, h -> {
            if (h.statusCode() == 200) {
                h.bodyHandler(bh -> {
                    JsonObject responce = new JsonObject(bh.toString());
                    TEST_TOKEN = responce.getString("ID");
                    httpClient.close();
                    latch.countDown();
                });
            }
        }).end(new JsonObject().put("Rules", Utils.readResource("default_rules.hcl")).encode());
        latch.await(1, TimeUnit.SECONDS);
        if (TEST_TOKEN == null) {
            throw new RuntimeException("Starting consul fails");
        }
    }

    @AfterClass
    public static void stopConsul() {
        consul.close();
    }

    @Test
    public void testKV1() throws InterruptedException {
        testClient.putValue("foo/bar", "value", handleResult(h1 -> {
            testClient.getValue("foo/bar", handleResult(pair -> {
                assertEquals("foo/bar", pair.getKey());
                assertEquals("value", pair.getValue());
                testComplete();
            }));
        }));
        await(1, TimeUnit.SECONDS);
    }

    @Test
    public void testKV2() throws InterruptedException {
        testClient.putValue("foo/bars1", "value1", handleResult(h1 -> {
            testClient.putValue("foo/bars2", "value2", handleResult(h2 -> {
                testClient.getValues("foo/bars", handleResult(h3 -> {
                    List<KeyValuePair> expected = Arrays.asList(
                            new KeyValuePair("foo/bars1", "value1"),
                            new KeyValuePair("foo/bars2", "value2")
                    );
                    assertEquals(expected, h3);
                    testComplete();
                }));
            }));
        }));
        await(1, TimeUnit.SECONDS);
    }

    @Test
    public void testKV3() throws InterruptedException {
        testClient.putValue("foo/toDel", "value", handleResult(h1 -> {
            testClient.deleteValue("foo/toDel", handleResult(h2 -> {
                testClient.getValue("foo/toDel", h3 -> {
                    if (h3.failed()) {
                        testComplete();
                    }
                });
            }));
        }));
        await(1, TimeUnit.SECONDS);
    }

    @Test
    public void testEvents1() throws InterruptedException {
        testClient.fireEvent(Event.empty().withName("custom-event").withPayload("content"), handleResult(h1 -> {
            assertEquals(h1.getName(), "custom-event");
            assertEquals(h1.getPayload(), "content");
            String evId = h1.getId();
            testClient.listEvents(handleResult(h2 -> {
                long cnt = h2.stream().map(Event::getId).filter(id -> id.equals(evId)).count();
                assertEquals(cnt, 1);
                testComplete();
            }));
        }));
        await(1, TimeUnit.SECONDS);
    }

    @Test
    public void testService1() throws InterruptedException {
        ServiceOptions service = new ServiceOptions()
                .setName("serviceName")
                .setTags(Arrays.asList("tag1", "tag2"))
                .setCheckOptions(CheckOptions.ttl("10s"))
                .setAddress("10.0.0.1")
                .setPort(8080);
        waitFor(2);
        testClient.registerService(service, handleResult(h1 -> {
            testClient.localServices(handleResult(h2 -> {
                ServiceInfo s = h2.stream().filter(i -> "serviceName".equals(i.getName())).findFirst().get();
                assertEquals(s.getTags().get(1), "tag2");
                assertEquals(s.getAddress(), "10.0.0.1");
                assertEquals(s.getPort(), 8080);
                complete();
            }));
            testClient.localChecks(handleResult(h2 -> {
                CheckInfo c = h2.stream().filter(i -> "serviceName".equals(i.getServiceName())).findFirst().get();
                assertEquals(c.getId(), "service:serviceName");
                complete();
            }));
        }));
        await(1, TimeUnit.SECONDS);
    }

    @Test
    public void testService2() {
        testClient.infoService("consul", handleResult(services -> {
            long cnt = services.stream().filter(s -> s.getName().equals("consul")).count();
            assertEquals(cnt, 1);
            testComplete();
        }));
        await(1, TimeUnit.SECONDS);
    }

    @Test
    public void testCheck1() {
        CheckOptions check = CheckOptions.ttl("10s").setId("checkId").setName("checkName");
        testClient.registerCheck(check, handleResult(h1 -> {
            testClient.localChecks(handleResult(h2 -> {
                CheckInfo c = h2.stream().filter(i -> "checkName".equals(i.getName())).findFirst().get();
                testClient.updateCheck(c.setOutput("outputMessage"), handleResult(h3 -> {
                    testClient.localChecks(handleResult(h4 -> {
                        CheckInfo c2 = h4.stream().filter(i -> "checkName".equals(i.getName())).findFirst().get();
                        assertEquals(c2.getOutput(), "outputMessage");
                        testClient.deregisterCheck(c2.getId(), handleResult(h5 -> testComplete()));
                    }));
                }));
            }));
        }));
        await(1, TimeUnit.SECONDS);
    }

    @Test
    public void test3() throws InterruptedException {
        masterClient.createAclToken(AclToken.empty(), h -> {
            String id = h.result();
            masterClient.infoAclToken(id, handleResult(info -> {
                assertEquals(id, info.getId());
                testComplete();
            }));
        });
        await(1, TimeUnit.SECONDS);
    }

    @Test
    public void test4() throws InterruptedException {
        masterClient.createAclToken(AclToken.empty(), h -> {
            String id = h.result();
            masterClient.destroyAclToken(id, handleResult(destroyed -> testComplete()));
        });
        await(1, TimeUnit.SECONDS);
    }

    protected <T> Handler<AsyncResult<T>> handleResult(Handler<T> resultHandler) {
        return h -> {
            if (h.succeeded()) {
                resultHandler.handle(h.result());
            } else {
                throw new RuntimeException(h.cause());
            }
        };
    }
}
