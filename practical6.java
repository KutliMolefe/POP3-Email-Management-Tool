import java.io.*;
import java.net.*;
import java.util.*;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.Base64;
import java.nio.file.*;

public class practical6 {
   
    private static final String SMTP_SERVER = "localhost";
    private static final int SMTP_PORT = 25;
    private static final String FROM_EMAIL = "alarm@localhost";
    private static final String TO_EMAIL = "kutli@localhost";
    private static final int HIGH_ALERT_THRESHOLD = 3;
    private static final int RESEND_INTERVAL_MINUTES = 5;
    private static final String ATTACHMENT_PATH = "security.jpg"; 

    
   

    private static final Map<Character, String> SENSOR_MAP = new HashMap<Character, String>() {
        {
            put('1', "Front Gate movement");
            put('2', "Backyard movement");
            put('3', "Living Room Motion detected");
            put('4', "Bedroom Motion detected");
            put('5', "Kitchen Window Motion detected");
        }
    };

    private static final ConcurrentHashMap<String, AlertStatus> activeAlerts = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private static class AlertStatus {
        int triggerCount;
        LocalDateTime lastSentTime;
        ScheduledFuture<?> resendTask;

        AlertStatus(int triggerCount, LocalDateTime lastSentTime) {
            this.triggerCount = triggerCount;
            this.lastSentTime = lastSentTime;
        }
    }

    public static void main(String[] args) {
        System.out.println("Alarm System Email Notifier");
        System.out.println("Press sensor keys (1-5) or 'q' to quit");
        System.out.println("Sensor Mapping:");
        SENSOR_MAP.forEach((k, v) -> System.out.println(k + " - " + v));

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("Waiting for sensor trigger: ");
            String input = scanner.nextLine().toLowerCase();

            if (input.equals("q")) {
                System.out.println("Exiting alarm system...");
                scheduler.shutdown();
                break;
            }

            if (input.length() == 1) {
                char sensorKey = input.charAt(0);
                if (SENSOR_MAP.containsKey(sensorKey)) {
                    handleSensorTrigger(sensorKey);
                } else {
                    System.out.println("Invalid sensor key. Try 1-5 or 'q' to quit.");
                }
            }
        }
        scanner.close();
    }

    private static void handleSensorTrigger(char sensorKey) {
        String sensorName = SENSOR_MAP.get(sensorKey);
        System.out.println("ALERT: " + sensorName + " triggered!");

        AlertStatus status = activeAlerts.compute(sensorName,
                (key, existing) -> existing == null ? new AlertStatus(1, LocalDateTime.now())
                        : new AlertStatus(existing.triggerCount + 1, LocalDateTime.now()));

        if (status.triggerCount >= HIGH_ALERT_THRESHOLD) {
            handleHighAlert(sensorName, status);
        } else {
            sendAlertEmail(sensorName, status.triggerCount);
        }
    }

    private static void handleHighAlert(String sensorName, AlertStatus status) {
        System.out.printf("HIGH ALERT: %s triggered %d times!%n", sensorName, status.triggerCount);

        if (status.resendTask != null) {
            status.resendTask.cancel(false);
        }

        sendAlertEmail(sensorName, status.triggerCount);

        status.resendTask = scheduler.scheduleAtFixedRate(
                () -> {
                    if (activeAlerts.containsKey(sensorName)) {
                        System.out.println("Resending HIGH ALERT for " + sensorName);
                        sendAlertEmail(sensorName, activeAlerts.get(sensorName).triggerCount);
                    }
                },
                RESEND_INTERVAL_MINUTES,
                RESEND_INTERVAL_MINUTES,
                TimeUnit.MINUTES);
    }

    private static void sendAlertEmail(String sensorName, int triggerCount) {
        String status = triggerCount >= HIGH_ALERT_THRESHOLD ? "HIGH ALERT" : "Triggered";
        String subject = String.format("%s: %s Triggered%s",
                triggerCount >= HIGH_ALERT_THRESHOLD ? "HIGH ALARM" : "ALARM",
                sensorName,
                triggerCount >= HIGH_ALERT_THRESHOLD ? " " + triggerCount + " times" : "");

        String body = String.format("""
                ======================
                ALARM NOTIFICATION
                ======================
                Sensor:    %s
                Trigger #: %d
                Status:    %s
                Time:      %s

                ACTION REQUIRED: Verify property immediately!
                ======================
                """,
                sensorName,
                triggerCount,
                status,
                LocalDateTime.now());

        try {
            sendEmail(subject, body);
            System.out.println("Notification email sent to " + TO_EMAIL);
        } catch (IOException e) {
            System.err.println("Failed to send email: " + e.getMessage());
        }
    }

    private static void sendEmail(String subject, String body) throws IOException {
        try (Socket socket = new Socket(SMTP_SERVER, SMTP_PORT);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            // SMTP Handshake
            validateResponse(in, "220", "SMTP server error");
            out.println("EHLO " + InetAddress.getLocalHost().getHostName());
            readMultiLineResponse(in);

            

            if (ATTACHMENT_PATH != null && Files.exists(Paths.get(ATTACHMENT_PATH))) {
                out.println("Content-Type: multipart/mixed; boundary=\"ALARM-BOUNDARY\"");
                out.println();

                
                out.println("--ALARM-BOUNDARY");
                out.println("Content-Type: text/plain; charset=UTF-8");
                out.println();
                out.println(body);
                out.println();

                
                attachFile(out, ATTACHMENT_PATH);
                out.println("--ALARM-BOUNDARY--");
            } else {
                out.println("Content-Type: text/plain; charset=UTF-8");
                out.println();
                out.println(body);
            }

            out.println(".");
            validateResponse(in, "250", "Message sending failed");

            sendCommand(out, in, "QUIT", "221", "QUIT failed");
        }
    }


    private static void attachFile(PrintWriter out, String filePath) throws IOException {
        Path path = Paths.get(filePath);
        String fileName = path.getFileName().toString();
        byte[] fileData = Files.readAllBytes(path);
        String encoded = Base64.getEncoder().encodeToString(fileData);

        out.println("-ALARM-");
        out.println("Content-Type: application/octet-stream");
        out.println("Content-Disposition: attachment; filename=\"" + fileName + "\"");
        out.println("Content-Transfer-Encoding: base64");
        out.println();

       
        for (int i = 0; i < encoded.length(); i += 76) {
            out.println(encoded.substring(i, Math.min(i + 76, encoded.length())));
        }
        out.println();
    }

    

    private static void sendCommand(PrintWriter out, BufferedReader in,
            String command, String expectedCode, String errorMsg) throws IOException {
        out.println(command);
        validateResponse(in, expectedCode, errorMsg);
    }

    private static void validateResponse(BufferedReader in, String expectedCode, String errorMsg) throws IOException {
        String response = in.readLine();
        if (!response.startsWith(expectedCode)) {
            throw new IOException(errorMsg + ": " + response);
        }
    }

    private static String readMultiLineResponse(BufferedReader in) throws IOException {
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            response.append(line).append("\n");
            if (line.startsWith("250 "))
                break;
        }
        return response.toString();
    }
}