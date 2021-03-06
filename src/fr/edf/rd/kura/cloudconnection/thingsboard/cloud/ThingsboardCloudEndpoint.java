package fr.edf.rd.kura.cloudconnection.thingsboard.cloud;

import static fr.edf.rd.kura.cloudconnection.thingsboard.util.Utils.catchAll;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Matcher;

import org.eclipse.kura.KuraConnectException;
import org.eclipse.kura.KuraDisconnectException;
import org.eclipse.kura.KuraException;
import org.eclipse.kura.KuraNotConnectedException;
import org.eclipse.kura.cloud.CloudConnectionEstablishedEvent;
import org.eclipse.kura.cloud.CloudConnectionLostEvent;
import org.eclipse.kura.cloudconnection.CloudConnectionManager;
import org.eclipse.kura.cloudconnection.CloudEndpoint;
import org.eclipse.kura.cloudconnection.listener.CloudConnectionListener;
import org.eclipse.kura.cloudconnection.listener.CloudDeliveryListener;
import org.eclipse.kura.cloudconnection.message.KuraMessage;
import org.eclipse.kura.cloudconnection.subscriber.listener.CloudSubscriberListener;
import org.eclipse.kura.configuration.ConfigurableComponent;
import org.eclipse.kura.configuration.ConfigurationService;
import org.eclipse.kura.core.util.MqttTopicUtil;
import org.eclipse.kura.data.DataService;
import org.eclipse.kura.data.listener.DataServiceListener;
import org.eclipse.kura.message.KuraPayload;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import fr.edf.rd.kura.cloudconnection.thingsboard.publisher.PublishOptions;
import fr.edf.rd.kura.cloudconnection.thingsboard.subscriber.SubscribeOptions;

public class ThingsboardCloudEndpoint
        implements CloudEndpoint, CloudConnectionManager, DataServiceListener, ConfigurableComponent {

    private static final Logger logger = LoggerFactory.getLogger(ThingsboardCloudEndpoint.class);

    private DataService dataService;
    private EventAdmin eventAdmin;
    private ComponentContext componentContext;

    private final Set<CloudDeliveryListener> cloudDeliveryListeners = new CopyOnWriteArraySet<>();
    private final Set<CloudConnectionListener> cloudConnectionListeners = new CopyOnWriteArraySet<>();
    private final Map<SubscribeOptions, Set<CloudSubscriberListener>> subscribers = new ConcurrentHashMap<>();

    public void setDataService(final DataService dataService) {
        this.dataService = dataService;
    }

    public void unsetDataService(final DataService dataService) {
        this.dataService = null;
    }

    public void setEventAdmin(final EventAdmin eventAdmin) {
        this.eventAdmin = eventAdmin;
    }

    public void unsetEventAdmin(final EventAdmin eventAdmin) {
        this.eventAdmin = null;
    }

    public void activated(final ComponentContext componentContext) {
        logger.info("activating...");

        this.componentContext = componentContext;
        this.dataService.addDataServiceListener(this);

        if (this.dataService.isConnected()) {
            onConnectionEstablished();
        }

        logger.info("activating...done");
    }

    public void updated() {
        logger.info("updating...");
        logger.info("updating...done");
    }

    public void deactivated() {
        logger.info("deactivating...");

        this.dataService.removeDataServiceListener(this);

        synchronized (this) {
            this.subscribers.keySet().forEach(this::unsubscribe);
        }

        logger.info("deactivating...done");
    }

    @Override
    public void connect() throws KuraConnectException {
        this.dataService.connect();
    }

    @Override
    public void disconnect() throws KuraDisconnectException {
        this.dataService.disconnect(10);
    }

    @Override
    public boolean isConnected() {
        return this.dataService.isConnected();
    }

    @Override
    public void registerCloudConnectionListener(CloudConnectionListener cloudConnectionListener) {
        this.cloudConnectionListeners.add(cloudConnectionListener);
    }

    @Override
    public void unregisterCloudConnectionListener(CloudConnectionListener cloudConnectionListener) {
        this.cloudConnectionListeners.remove(cloudConnectionListener);
    }

    @Override
    public String publish(final KuraMessage message) throws KuraException {

        PublishOptions options = new PublishOptions(message.getProperties());
        KuraPayload kuraPayload = message.getPayload();

        final int qos = options.getQos().getValue();

        logger.info(options.getDeviceId());

        final String deviceId = fillDeviceIdPlaceholders(options.getDeviceId(), message);

        // Publish device on thingsboard device connect API
        JsonObject connectJson = new JsonObject();
        connectJson.add("device", deviceId);
        byte[] connectPayload = connectJson.toString().getBytes(StandardCharsets.UTF_8);
        this.dataService.publish(Constants.CONNECT_TOPIC, connectPayload, qos, options.getRetain(),
                options.getPriority());

        // Build and publish device data
        JsonObject values = new JsonObject();
        Iterator<String> metrics = kuraPayload.metricsIterator();

        while (metrics.hasNext()) {
            String name = metrics.next();
            Object value = kuraPayload.getMetric(name);
            if (value instanceof Boolean) {
                values.add(name, (Boolean) value);
            } else if (value instanceof Double) {
                values.add(name, (Double) value);
            } else if (value instanceof Float) {
                values.add(name, (Float) value);
            } else if (value instanceof Integer) {
                values.add(name, (Integer) value);
            } else if (value instanceof Long) {
                values.add(name, (Long) value);
            } else {
                values.add(name, value.toString());
            }
        }

        if (kuraPayload.getPosition() != null) {
            values.add("latitude", kuraPayload.getPosition().getLatitude());
            values.add("longitude", kuraPayload.getPosition().getLongitude());
        }

        JsonObject deviceJson = new JsonObject();
        deviceJson.add("ts", kuraPayload.getTimestamp().getTime());
        deviceJson.add("values", values);

        JsonArray array = new JsonArray();
        array.add(deviceJson);

        JsonObject json = new JsonObject();
        json.add(deviceId, array);

        byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);

        final int id = this.dataService.publish(Constants.TELEMETRY_TOPIC, payload, qos, options.getRetain(),
                options.getPriority());

        if (qos == 0) {
            return null;
        } else {
            return Integer.toString(id);
        }

    }

    private String fillDeviceIdPlaceholders(String deviceId, KuraMessage message) {

        Matcher matcher = Constants.DEVICE_ID_PATTERN.matcher(deviceId);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            Map<String, Object> properties = message.getProperties();
            if (properties.containsKey(matcher.group(1))) {
                String replacement = matcher.group(0);

                Object value = properties.get(matcher.group(1));
                if (replacement != null) {
                    matcher.appendReplacement(buffer, value.toString());
                }
            }
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    @Override
    public synchronized void registerSubscriber(final Map<String, Object> subscriptionProperties,
            final CloudSubscriberListener cloudSubscriberListener) {

        final SubscribeOptions subscribeOptions;

        try {
            subscribeOptions = new SubscribeOptions(subscriptionProperties);
        } catch (final Exception e) {
            throw new IllegalArgumentException(e);
        }

        registerSubscriber(subscribeOptions, cloudSubscriberListener);
    }

    public synchronized void registerSubscriber(final SubscribeOptions subscribeOptions,
            final CloudSubscriberListener cloudSubscriberListener) {

        final Set<CloudSubscriberListener> listeners = this.subscribers.computeIfAbsent(subscribeOptions,
                e -> new CopyOnWriteArraySet<>());

        listeners.add(cloudSubscriberListener);

        subscribe(subscribeOptions);
    }

    @Override
    public synchronized void unregisterSubscriber(CloudSubscriberListener cloudSubscriberListener) {
        final Set<SubscribeOptions> toUnsubscribe = new HashSet<>();

        this.subscribers.entrySet().removeIf(e -> {

            final Set<CloudSubscriberListener> listeners = e.getValue();

            listeners.remove(cloudSubscriberListener);

            if (listeners.isEmpty()) {
                toUnsubscribe.add(e.getKey());
                return true;
            } else {
                return false;
            }
        });

        toUnsubscribe.forEach(this::unsubscribe);

    }

    @Override
    public void registerCloudDeliveryListener(CloudDeliveryListener cloudDeliveryListener) {
        this.cloudDeliveryListeners.add(cloudDeliveryListener);
    }

    @Override
    public void unregisterCloudDeliveryListener(CloudDeliveryListener cloudDeliveryListener) {
        this.cloudDeliveryListeners.remove(cloudDeliveryListener);
    }

    @Override
    public void onConnectionEstablished() {
        this.cloudConnectionListeners.forEach(catchAll(CloudConnectionListener::onConnectionEstablished));

        synchronized (this) {
            this.subscribers.keySet().forEach(this::subscribe);
        }

        postConnectionStateChangeEvent(true);
    }

    @Override
    public void onDisconnecting() {
        // do nothing
    }

    @Override
    public void onDisconnected() {
        this.cloudConnectionListeners.forEach(catchAll(CloudConnectionListener::onDisconnected));

        postConnectionStateChangeEvent(false);
    }

    @Override
    public void onConnectionLost(Throwable cause) {
        this.cloudConnectionListeners.forEach(catchAll(CloudConnectionListener::onConnectionLost));

        postConnectionStateChangeEvent(false);
    }

    @Override
    public void onMessageArrived(final String topic, final byte[] payload, final int qos, final boolean retained) {
        logger.info("message arrived on topic {}", topic);

        final KuraPayload kuraPayload = new KuraPayload();

        String stringJson = new String(payload, StandardCharsets.UTF_8);
        JsonObject json = Json.parse(stringJson).asObject();

        String deviceId = json.get("device").asString();
        kuraPayload.addMetric("assetName", deviceId);

        JsonObject params = Json.parse(json.get("params").asString()).asObject();

        for (JsonObject.Member member : params) {
            String name = member.getName();
            JsonValue value = member.getValue();

            if (value.isArray()) {
                // TODO
            } else if (value.isBoolean()) {
                kuraPayload.addMetric(name, value.asBoolean());
            } else if (value.isNumber()) {
                kuraPayload.addMetric(name, value.asDouble());
            } else if (value.isObject()) {
                // TODO
            } else if (value.isString()) {
                kuraPayload.addMetric(name, value.asString());
            }

        }

        final Map<String, Object> messageProperties = Collections.singletonMap(Constants.TOPIC_PROP_NAME, topic);

        final KuraMessage message = new KuraMessage(kuraPayload, messageProperties);

        for (final Entry<SubscribeOptions, Set<CloudSubscriberListener>> e : this.subscribers.entrySet()) {
            if (MqttTopicUtil.isMatched(Constants.SUBSCRIBE_TOPIC, topic)) {
                e.getValue().forEach(catchAll(l -> l.onMessageArrived(message)));
            }
        }
    }

    @Override
    public void onMessagePublished(final int messageId, final String topic) {
        // do nothing
    }

    @Override
    public void onMessageConfirmed(int messageId, String topic) {
        this.cloudDeliveryListeners.forEach(catchAll(l -> l.onMessageConfirmed(Integer.toString(messageId))));
    }

    private void postConnectionStateChangeEvent(final boolean isConnected) {

        final Map<String, Object> eventProperties = Collections.singletonMap("cloud.service.pid",
                (String) this.componentContext.getProperties().get(ConfigurationService.KURA_SERVICE_PID));

        final Event event = isConnected ? new CloudConnectionEstablishedEvent(eventProperties)
                : new CloudConnectionLostEvent(eventProperties);
        this.eventAdmin.postEvent(event);
    }

    private void subscribe(final SubscribeOptions options) {
        try {
            final String topicFilter = Constants.SUBSCRIBE_TOPIC;
            final int qos = options.getQos().getValue();

            logger.info("subscribing to {} with qos {}", topicFilter, qos);
            this.dataService.subscribe(topicFilter, qos);
        } catch (final KuraNotConnectedException e) {
            logger.debug("failed to subscribe, DataService not connected");
        } catch (final Exception e) {
            logger.warn("failed to subscribe", e);
        }
    }

    private void unsubscribe(final SubscribeOptions options) {
        try {
            final String topicFilter = Constants.SUBSCRIBE_TOPIC;

            logger.info("unsubscribing from {}", topicFilter);
            this.dataService.unsubscribe(topicFilter);
        } catch (final KuraNotConnectedException e) {
            logger.debug("failed to unsubscribe, DataService not connected");
        } catch (final Exception e) {
            logger.warn("failed to unsubscribe", e);
        }
    }
}
