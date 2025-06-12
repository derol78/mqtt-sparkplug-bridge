package com.exemple;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import org.eclipse.tahu.message.model.SparkplugBPayload;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;

public class MqttSubscriberPublisher implements MqttCallbackExtended {

    private IMqttClient subscriberClient;
    private IMqttClient publisherClient;
    private String subscribeTopic = "spBv1.0/BT00/NDATA/GW2.0";
    private String publishTopic = "topicB";
    private String brokerUrl = "tcp://192.168.20.10:1883"; // Byt ut vid behov
    private String clientIdSubscriber = "JavaSubscriber";
    private String clientIdPublisher = "JavaPublisher";

    public MqttSubscriberPublisher() {
        try {
            // Prenumerationsklient
            MemoryPersistence subPersistence = new MemoryPersistence();
            subscriberClient = new MqttClient(brokerUrl, clientIdSubscriber, subPersistence);
            subscriberClient.setCallback(this);

            // Publiceringsklient
            MemoryPersistence pubPersistence = new MemoryPersistence();
            publisherClient = new MqttClient(brokerUrl, clientIdPublisher, pubPersistence);

            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);
            connOpts.setAutomaticReconnect(true);
            connOpts.setConnectionTimeout(30);
            connOpts.setKeepAliveInterval(30);

            System.out.println("Ansluter till MQTT-mäklare: " + brokerUrl);
            subscriberClient.connect(connOpts);
            publisherClient.connect(connOpts);
            System.out.println("Ansluten");

            subscriberClient.subscribe(subscribeTopic, 0);
            System.out.println("Prenumererar på ämne: " + subscribeTopic);

        } catch (MqttException me) {
            System.err.println("Orsak: " + me.getReasonCode());
            System.err.println("Meddelande: " + me.getMessage());
            System.err.println("Lokalt meddelande: " + me.getLocalizedMessage());
            System.err.println("Cusor: " + me.getCause());
            System.err.println("Exception: " + me);
            me.printStackTrace();
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        System.out.println("Anslutning slutförd. Återansluten: " + reconnect + ", Server URI: " + serverURI);
    }

    @Override
    public void connectionLost(Throwable cause) {
        System.out.println("Anslutningen till MQTT-mäklaren bröts! - kommer att försöka återansluta automatiskt.");
        cause.printStackTrace();
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        //System.out.println("Meddelande mottaget i ämne: " + topic);
        byte[] payload = message.getPayload();

        SparkplugBPayloadDecoder decoder = new SparkplugBPayloadDecoder();
        try {
            SparkplugBPayload inboundPayload = decoder.buildFromByteArray(payload, null);

            ObjectMapper mapper = new ObjectMapper();
            mapper.setSerializationInclusion(Include.NON_NULL);
            String payloadString = mapper.writeValueAsString(inboundPayload); // Konvertera Sparkplug B till JSON-sträng
            //System.out.println("Avkodat Sparkplug B-meddelande (JSON):");
            //System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readValue(payloadString, Object.class))); // Pretty print JSON

            // Analysera JSON för att hitta ett metrik-namn som innehåller "RcdMade"
            boolean executeScript = false;
            if (inboundPayload.getMetrics() != null) {
                for (org.eclipse.tahu.message.model.Metric metric : inboundPayload.getMetrics()) {
                    if (metric.getName() != null && metric.getName().contains("RcdMade")) {
                        executeScript = true;
                        break;
                    }
                }
            }

            if (executeScript) {
                System.out.println("Hittade ett metrik-namn som innehåller 'RcdMade'. Exekverar skriptet asynkront...");
                executeArchiveSyncScript();
            } else {
                //System.out.println("Inget metrik-namn innehöll 'EK1'. Skriptet kommer inte att exekveras.");
                // Här kan du eventuellt lägga till logik för att publicera det ursprungliga meddelandet vidare om det behövs
                publishMessage(payload); // Publicera det ursprungliga Sparkplug B-meddelandet
            }

        } catch (Exception e) {
            System.err.println("Fel vid hantering av meddelande: " + e.getMessage());
            e.printStackTrace();
            // Hantera felaktiga meddelanden här
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        System.out.println("Meddelande publicerat: " + token);
    }

    public void publishMessage(byte[] payload) {
        MqttMessage message = new MqttMessage(payload);
        message.setQos(0); // Quality of Service nivå
        try {
            publisherClient.publish(publishTopic, message);
            //System.out.println("Avkodat meddelande (som JSON) publicerat till ämne: " + publishTopic);
        } catch (MqttException me) {
            System.err.println("Fel vid publicering: " + me.getMessage());
            me.printStackTrace();
        }
    }

    private void executeArchiveSyncScript() {
        String scriptPath = "/home/fn/ArchiveSync/runArchiveSync.sh";
        new Thread(() -> {
            try {
                System.out.println("Startar skriptet '" + scriptPath + "' i en separat tråd...");
                ProcessBuilder processBuilder = new ProcessBuilder("bash", scriptPath);
                Process process = processBuilder.start();

                // Läs ut skriptets output (valfritt)
                java.io.BufferedReader stdInput = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
                String s;
                System.out.println("Utskrift från skriptet:");
                while ((s = stdInput.readLine()) != null) {
                    System.out.println(s);
                }

                // Läs ut eventuella fel från skriptet
                java.io.BufferedReader stdError = new java.io.BufferedReader(new java.io.InputStreamReader(process.getErrorStream()));
                System.err.println("Fel från skriptet:");
                while ((s = stdError.readLine()) != null) {
                    System.err.println(s);
                }

                int exitCode = process.waitFor();
                System.out.println("Skriptet '" + scriptPath + "' avslutades med kod: " + exitCode);

                if (exitCode != 0) {
                    System.err.println("Skriptet avslutades med felkod: " + exitCode);
                }

            } catch (java.io.IOException e) {
                System.err.println("Fel vid exekvering av skriptet: " + e.getMessage());
                e.printStackTrace();
            } catch (InterruptedException e) {
                System.err.println("Exekvering av skriptet avbröts: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
        }).start();
        System.out.println("Skriptet '" + scriptPath + "' har startats asynkront.");
    }

    public static void main(String[] args) {
        new MqttSubscriberPublisher();
    }
}