import java.io.*;
import java.net.*;
import java.util.*;


public class POP3Client {
    private static final int MAX_FROM_LENGTH = 30;
    private static final int MAX_SUBJECT_LENGTH = 50;
    private static final int PREVIEW_LINES = 3;

    public static void main(String[] args) {
       
        String server = "localhost";
        int port = 110;
        String user = "pop3user";
        String pass = "Kutli@2002";

        Scanner sc = new Scanner(System.in);

        try (Socket socket = new Socket(server, port);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()))) {

            
            String response = readResponse(reader);
            if (!response.startsWith("+OK")) {
                throw new IOException("Server error: " + response);
            }

            
            boolean statSupported = checkStatSupport(writer, reader);

            
            if (!sendAndVerify(writer, reader, "USER " + user) ||
                    !sendAndVerify(writer, reader, "PASS " + pass)) {
                throw new IOException("Authentication failed");
            }

          
            Map<Integer, Integer> messageSizes = getMessageSizes(writer, reader);
            if (messageSizes.isEmpty()) {
                System.out.println("No messages found");
                sendCommand(writer, "QUIT");
                return;
            }

           
            System.out.println("\nRetrieving message headers...");
            Map<Integer, MessageInfo> messages = getAllMessageInfo(writer, reader, messageSizes.keySet());

            System.out.printf("\nFound %d messages\n", messages.size());

           
            List<Integer> messagesToDelete = new ArrayList<>();
            while (true) {
                System.out.println("\n=== Main Menu ===");
                System.out.println("1. List all messages");
                System.out.println("2. Filter messages");
                System.out.println("3. Select messages to delete");
                System.out.println("4. Preview messages");
                System.out.println("5. Confirm and delete selected");
                System.out.println("6. Reset selections");
                System.out.println("7. Quit");
                System.out.print("Enter choice: ");

                String choice = sc.nextLine().trim();

                switch (choice) {
                    case "1":
                        displayMessagesTable(messages, messageSizes);
                        break;

                    case "2":
                        filterMessages(messages, messageSizes, sc);
                        break;

                    case "3":
                        selectMessagesForDeletion(messages, messageSizes, messagesToDelete, sc);
                        break;

                    case "4":
                        previewMessage(messages, sc);
                        break;

                    case "5":
                        if (!messagesToDelete.isEmpty()) {
                            processDeletions(writer, reader, messages, messageSizes, messagesToDelete, sc);
                        } else {
                            System.out.println("No messages selected for deletion");
                        }
                        break;

                    case "6":
                        messagesToDelete.clear();
                        System.out.println("Selection cleared");
                        break;

                    case "7":
                        sendCommand(writer, "QUIT");
                        readResponse(reader);
                        System.out.println("Goodbye!");
                        return;

                    default:
                        System.out.println("Invalid choice");
                }
            }

        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
        } finally {
            sc.close();
        }
    }

    private static void processDeletions(BufferedWriter writer, BufferedReader reader,
            Map<Integer, MessageInfo> messages, Map<Integer, Integer> messageSizes,
            List<Integer> messagesToDelete, Scanner sc) throws IOException {
        System.out.println("\n=== Confirm Deletion ===");
        System.out.println("About to delete " + messagesToDelete.size() + " messages:");

        for (int msgId : messagesToDelete) {
            MessageInfo info = messages.get(msgId);
            System.out.printf("  %d: %s - %s (%s)\n",
                    msgId,
                    truncate(info.from, MAX_FROM_LENGTH),
                    truncate(info.subject, MAX_SUBJECT_LENGTH),
                    formatSize(messageSizes.get(msgId)));
        }

        System.out.print("\nConfirm deletion? (y/n): ");
        String confirm = sc.nextLine().trim().toLowerCase();

        if (confirm.equals("y")) {
            
            for (int msgId : messagesToDelete) {
                sendCommand(writer, "DELE " + msgId);
                String response = readResponse(reader);
                if (!response.startsWith("+OK")) {
                    System.err.println("Failed to delete message " + msgId + ": " + response);
                }
            }

           
            sendCommand(writer, "QUIT");
            String response = readResponse(reader);
            System.out.println("Messages successfully deleted: " + response);
            System.exit(0); 
        } else {
            
            sendCommand(writer, "RSET");
            readResponse(reader);
            System.out.println("Deletion cancelled");
        }
    }

    private static boolean checkStatSupport(BufferedWriter writer, BufferedReader reader) throws IOException {
        sendCommand(writer, "CAPA");
        String response = readResponse(reader);
        if (response.startsWith("+OK")) {
            String line;
            while (!(line = reader.readLine()).equals(".")) {
                if (line.equalsIgnoreCase("STAT")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<Integer, Integer> getMessageSizes(BufferedWriter writer, BufferedReader reader)
            throws IOException {
        Map<Integer, Integer> messageSizes = new HashMap<>();
        sendCommand(writer, "LIST");
        String response = readResponse(reader);
        if (response.startsWith("+OK")) {
            String line;
            while (!(line = reader.readLine()).equals(".")) {
                String[] parts = line.split(" ");
                if (parts.length >= 2) {
                    try {
                        messageSizes.put(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid LIST format: " + line);
                    }
                }
            }
        }
        return messageSizes;
    }

    private static Map<Integer, MessageInfo> getAllMessageInfo(BufferedWriter writer, BufferedReader reader,
            Set<Integer> messageIds) throws IOException {
        Map<Integer, MessageInfo> messages = new HashMap<>();
        for (int msgId : messageIds) {
            MessageInfo info = new MessageInfo();
            sendCommand(writer, "TOP " + msgId + " " + (5 + PREVIEW_LINES));
            String response = readResponse(reader);
            if (!response.startsWith("+OK")) {
                System.err.println("TOP failed for message " + msgId + ": " + response);
                continue;
            }

            boolean inHeaders = true;
            String line;
            StringBuilder currentHeader = null;
            String currentHeaderName = null;

            while (!(line = reader.readLine()).equals(".")) {
                if (inHeaders) {
                   
                    if (line.startsWith(" ") || line.startsWith("\t")) {
                        if (currentHeader != null) {
                            currentHeader.append(" ").append(line.trim());
                        }
                        continue;
                    }

                   
                    if (currentHeader != null) {
                        storeHeader(info, currentHeaderName, currentHeader.toString());
                        currentHeader = null;
                    }

                  
                    if (line.isEmpty()) {
                        inHeaders = false;
                        continue;
                    }

                    
                    int colonPos = line.indexOf(':');
                    if (colonPos > 0) {
                        currentHeaderName = line.substring(0, colonPos).trim();
                        currentHeader = new StringBuilder(line.substring(colonPos + 1).trim());
                    }
                } else {
                    if (info.previewLines.size() < PREVIEW_LINES) {
                        info.previewLines.add(line);
                    }
                }
            }

            if (currentHeader != null) {
                storeHeader(info, currentHeaderName, currentHeader.toString());
            }

            messages.put(msgId, info);
        }
        return messages;
    }

    private static void storeHeader(MessageInfo info, String headerName, String headerValue) {
        if (headerName == null)
            return;

        switch (headerName.toLowerCase()) {
            case "from":
                info.from = cleanHeader(headerValue);
                break;
            case "subject":
                info.subject = cleanHeader(headerValue);
                break;
            case "date":
                info.date = cleanHeader(headerValue);
                break;
        }
    }

    private static void displayMessagesTable(Map<Integer, MessageInfo> messages,
            Map<Integer, Integer> messageSizes) {
        System.out.println("\n=== Email Messages ===");
        printTableHeader();

        for (Map.Entry<Integer, MessageInfo> entry : messages.entrySet()) {
            int msgId = entry.getKey();
            MessageInfo info = entry.getValue();
            printMessageRow(msgId, info.from, info.subject, messageSizes.get(msgId), info.date);
        }
        printTableFooter();
    }

    private static void filterMessages(Map<Integer, MessageInfo> messages,
            Map<Integer, Integer> messageSizes, Scanner sc) {
        System.out.println("\nFilter Options:");
        System.out.println("1. By sender");
        System.out.println("2. By subject");
        System.out.println("3. By size range");
        System.out.print("Enter choice: ");

        String choice = sc.nextLine().trim();
        System.out.print("Enter filter text: ");
        String filterText = sc.nextLine().trim().toLowerCase();

        System.out.println("\nMatching messages:");
        printTableHeader();

        for (Map.Entry<Integer, MessageInfo> entry : messages.entrySet()) {
            int msgId = entry.getKey();
            MessageInfo info = entry.getValue();
            int size = messageSizes.get(msgId);

            boolean matches = false;
            switch (choice) {
                case "1":
                    matches = info.from.toLowerCase().contains(filterText);
                    break;
                case "2":
                    matches = info.subject.toLowerCase().contains(filterText);
                    break;
                case "3":
                    try {
                        int minSize = Integer.parseInt(filterText);
                        matches = size >= minSize;
                    } catch (NumberFormatException e) {
                        System.out.println("Invalid size format");
                        return;
                    }
                    break;
                default:
                    System.out.println("Invalid choice");
                    return;
            }

            if (matches) {
                printMessageRow(msgId, info.from, info.subject, size, info.date);
            }
        }
        printTableFooter();
    }

    private static void selectMessagesForDeletion(Map<Integer, MessageInfo> messages,
            Map<Integer, Integer> messageSizes, List<Integer> messagesToDelete, Scanner sc) {
        System.out.println("\nSelection Options:");
        System.out.println("1. Select by ID");
        System.out.println("2. Select all");
        System.out.println("3. Clear selection");
        System.out.print("Enter choice: ");

        String choice = sc.nextLine().trim();

        switch (choice) {
            case "1":
                System.out.print("Enter message IDs to delete (comma-separated, ranges with '-'): ");
                String input = sc.nextLine().trim();
                if (input.equalsIgnoreCase("all")) {
                    messagesToDelete.addAll(messages.keySet());
                    System.out.println("All messages selected for deletion");
                    return;
                }

               
                for (String part : input.split(",")) {
                    part = part.trim();
                    if (part.contains("-")) {
                        try {
                            String[] range = part.split("-");
                            int start = Integer.parseInt(range[0].trim());
                            int end = Integer.parseInt(range[1].trim());
                            for (int i = start; i <= end; i++) {
                                if (messages.containsKey(i) && !messagesToDelete.contains(i)) {
                                    messagesToDelete.add(i);
                                }
                            }
                        } catch (NumberFormatException e) {
                            System.out.println("Invalid range: " + part);
                        }
                    } else {
                        try {
                            int msgId = Integer.parseInt(part);
                            if (messages.containsKey(msgId) && !messagesToDelete.contains(msgId)) {
                                messagesToDelete.add(msgId);
                            }
                        } catch (NumberFormatException e) {
                            System.out.println("Invalid ID: " + part);
                        }
                    }
                }
                System.out.println("Added " + messagesToDelete.size() + " messages to selection");
                break;

            case "2":
                messagesToDelete.addAll(messages.keySet());
                System.out.println("All messages selected for deletion");
                break;

            case "3":
                messagesToDelete.clear();
                System.out.println("Selection cleared");
                break;

            default:
                System.out.println("Invalid choice");
        }
    }

    private static void previewMessage(Map<Integer, MessageInfo> messages, Scanner sc) {
        System.out.print("Enter message ID to preview: ");
        try {
            int msgId = Integer.parseInt(sc.nextLine().trim());
            if (messages.containsKey(msgId)) {
                MessageInfo info = messages.get(msgId);
                System.out.println("\nMessage Preview:");
                System.out.println("From: " + info.from);
                System.out.println("Subject: " + info.subject);
                System.out.println("Date: " + info.date);
                System.out.println("\nBody preview:");
                for (String previewLine : info.previewLines) {
                    System.out.println("  " + previewLine);
                }
            } else {
                System.out.println("Invalid message ID");
            }
        } catch (NumberFormatException e) {
            System.out.println("Invalid message ID");
        }
    }

    private static boolean sendAndVerify(BufferedWriter writer, BufferedReader reader, String command)
            throws IOException {
        sendCommand(writer, command);
        String response = readResponse(reader);
        return response.startsWith("+OK");
    }

    private static void sendCommand(BufferedWriter writer, String command) throws IOException {
        writer.write(command + "\r\n");
        writer.flush();
    }

    private static String readResponse(BufferedReader reader) throws IOException {
        String response = reader.readLine();
        if (response == null)
            throw new IOException("Server disconnected");
        return response;
    }

    private static void printTableHeader() {
        System.out.println(
                "+-----+--------------------------------+------------------------------------+----------+---------------------+");
        System.out.println(
                "| ID  | From                            | Subject                            | Size     | Date                |");
        System.out.println(
                "+-----+--------------------------------+------------------------------------+----------+---------------------+");
    }

    private static void printMessageRow(int id, String from, String subject, int size, String date) {
        System.out.printf("| %-3d | %-30s | %-34s | %-8s | %-19s |\n",
                id,
                truncate(from, MAX_FROM_LENGTH),
                truncate(subject, MAX_SUBJECT_LENGTH),
                formatSize(size),
                date != null ? truncate(date, 19) : "");
    }

    private static void printTableFooter() {
        System.out.println(
                "+-----+--------------------------------+------------------------------------+----------+---------------------+");
    }

    private static String cleanHeader(String header) {
        if (header == null)
            return "";
        return header.replaceAll("[\\r\\n]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String truncate(String text, int maxLength) {
        if (text == null)
            return "";
        return text.length() > maxLength ? text.substring(0, maxLength - 3) + "..." : text;
    }

    private static String formatSize(int bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1048576)
            return (bytes / 1024) + " KB";
        return (bytes / 1048576) + " MB";
    }

    private static class MessageInfo {
        String from = "";
        String subject = "";
        String date = "";
        List<String> previewLines = new ArrayList<>();
    }
}