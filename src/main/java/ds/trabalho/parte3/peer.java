package ds.trabalho.parte3;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;

import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.BlockingQueue;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class message{
    int lamp_clock;
    String senderID;
    String originalSender;
    String message;

    public message(int l, String m, String s, String o){
        lamp_clock = l;
        message = m;
        senderID = s;
        originalSender = o;
    }
}

public class peer {
    // Basic attributes
    static AtomicReference<String> myIpPort;

    // Peer (mutual) attributes
    static Set<String> knownPeers;
    static List<message> history;
    static AtomicInteger lampClock;

    // Server data structures
    static int port; // The port on which the server should run
    static BlockingQueue<message> incomingQueue;

    // Client data structures
    static List<chatGrpc.chatBlockingStub> connections;
    static List<ManagedChannel> mChannels;
    static BlockingQueue<message> outgoingQueue;

    public peer() {
        myIpPort = new AtomicReference<>();

        knownPeers = Collections.synchronizedSet(new HashSet<>());
        history = Collections.synchronizedList(new Vector());
        lampClock = new AtomicInteger(0);

        connections = Collections.synchronizedList(new Vector<>());
        mChannels = Collections.synchronizedList(new Vector<>());

        incomingQueue = new ArrayBlockingQueue<>(100);
        outgoingQueue = new ArrayBlockingQueue<>(30);
    }

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        System.out.printf("Choose a port to start the server: ");
        port = Integer.parseInt(scanner.nextLine());

        peer p = new peer();

        try {
            myIpPort.set(InetAddress.getLocalHost().getHostAddress() + ":" + port);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        //known_peers.add("localhost:" + own_port);
        System.out.printf("New peer @ host=%s\n", port);
        new Thread(new p2pServer()).start();
        // wait a bit to start client
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        new Thread(new p2pClient()).start();
    }

    static class p2pServer implements Runnable {
        private Server server;
        static addresses addressesMessage;

        //public p2pServer() {        }

        private void start() throws IOException {
            //Scanner scanner = new Scanner(own_port).skip("localhost:");
            //port = scanner.nextInt();
            server = ServerBuilder.forPort(port)
                    .addService(new p2pImpl())
                    .build()
                    .start();
            System.out.println("Server started, listening on " + port);
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    // Use stderr here since the logger may have been reset by its JVM shutdown hook.
                    System.err.println("*** shutting down gRPC server since JVM is shutting down");
                    try {
                        p2pServer.this.stop();
                    } catch (InterruptedException e) {
                        e.printStackTrace(System.err);
                    }
                    System.err.println("*** server shut down");
                }
            });
        }

        private void stop() throws InterruptedException {
            if (server != null) {
                server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
            }
        }

        // Await termination on the main thread since the grpc library uses daemon threads.
        private void blockUntilShutdown() throws InterruptedException {
            if (server != null) {
                server.awaitTermination();
            }
        }

        // Main launches the server from the command line.
        @Override
        public void run() {
            final p2pServer server = new p2pServer();
            Thread inbox = new Thread(new inbox());
            inbox.start();
            try {
                server.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                server.blockUntilShutdown();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        static class p2pImpl extends chatGrpc.chatImplBase {
            @Override
            public void register(addresses request, StreamObserver<addresses> responseObserver) {
                addressesMessage = addresses.newBuilder().addAllMessage(knownPeers).build();

                Set<String> newPeers = new HashSet<>(request.getMessageList());
                newPeers.addAll(request.getMessageList());      // Get addresses from client
                newPeers.removeAll(knownPeers);
                for (String p : newPeers) {
                    mChannels.add(ManagedChannelBuilder.forTarget(p)
                            .usePlaintext()
                            .build());
                    connections.add(chatGrpc.newBlockingStub(mChannels.get(mChannels.size() - 1)));
                }

                knownPeers.addAll(newPeers);

                responseObserver.onNext(addressesMessage);
                responseObserver.onCompleted();
            }

            @Override
            public void send(chatMessage request, StreamObserver<chatMessage> messageStreamObserver) {
                message received = new message(request.getLampClock(), request.getContent(),
                        request.getSenderId(), request.getOriginalSender());
                //System.out.println("Server got: " + received.senderID + ": " + received.message);
                try {
                    incomingQueue.put(received);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                // Do I need to forward this message?
                if (received.senderID.equals(received.originalSender)){
                    if (!received.senderID.equals(String.valueOf(myIpPort))){
                        try {
                            outgoingQueue.put(new message(received.lamp_clock, received.message, String.valueOf(myIpPort), received.originalSender));
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }

                messageStreamObserver.onNext(request);
                messageStreamObserver.onCompleted();
            }
        }
    }

    static class p2pClient implements Runnable {

        //public p2pClient(){}

        @Override
        public void run() {
            System.out.printf("Write your command as 'command argument'\n");

            ManagedChannel mChannel;
            chatGrpc.chatBlockingStub blockingStub;

            addresses addresses_message;
            addresses addresses_reply;

            knownPeers.add(String.valueOf(myIpPort));
            mChannels.add(ManagedChannelBuilder.forTarget(String.valueOf(myIpPort))
                    .usePlaintext()
                    .build());
            connections.add(chatGrpc.newBlockingStub(mChannels.get(mChannels.size() - 1)));

            //p2pClient client = new p2pClient();
            String line = "0";
            Scanner scanner;
            String command;
            Scanner parser;

            Thread outgoingBox = new Thread(new outgoingBox());
            outgoingBox.start();

            while (true) {

                scanner = new Scanner(System.in);

                System.out.printf("$ ");

                line = scanner.nextLine();
                parser = new Scanner(line);
                command = parser.next();
                switch (command) {
                    case "get":
                        command = parser.next();
                        switch (command) {
                            case "peers":
                                System.out.println(knownPeers);
                                break;
                            case "history":
                                for (message m : history)
                                System.out.println("From: " + m.originalSender + " @" + m.lamp_clock + ": " + m.message);
                                break;
                            case "conns":
                                System.out.println(connections);
                                break;
                        }
                        break;
                    case "register":
                        try {
                            command = parser.next();
                            mChannel = ManagedChannelBuilder.forTarget(command)
                                    .usePlaintext()
                                    .build();
                            blockingStub = chatGrpc.newBlockingStub(mChannel);

                            addresses_message = addresses.newBuilder().addAllMessage(knownPeers).build();
                            addresses_reply = blockingStub.register(addresses_message);

                            Set<String> newPeers = new HashSet<>(addresses_reply.getMessageList());
                            newPeers.removeAll(knownPeers);

                            for (String p : newPeers) {
                                // ManagedChannels use resources like threads and TCP connections. To prevent leaking these
                                // resources the channel should be shut down when it will no longer be used. If it may be used
                                // again leave it running.
                                mChannels.add(ManagedChannelBuilder.forTarget(p)
                                        .usePlaintext()
                                        .build());
                                connections.add(chatGrpc.newBlockingStub(mChannels.get(mChannels.size() - 1)));
                            }

                            knownPeers.addAll(newPeers);
                            try {
                                mChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        } catch (Exception e) {
                            System.out.println("Could not fulfill request with peer. Potentially invalid peer.");
                            //e.printStackTrace();
                        }
                        break;
                    default:
                        //System.out.println("I'll send this message!");
                        try {
                            lampClock.incrementAndGet();
                            outgoingQueue.put(new message(lampClock.get(), line, String.valueOf(myIpPort), String.valueOf(myIpPort)));
                        } catch (InterruptedException e) {
                            System.out.println("Something wrong at trying to put new element");
                            e.printStackTrace();
                        }
                        break;
                }
            }
        }
    }

    static class inbox implements Runnable {
        //public inbox() {        }
        @Override
        public void run() {

            List<List<message>> processingTable = new ArrayList<List<message>>();
            message temp = new message(0, "", "", "");
            Set<String> compareSet;

            boolean already_exists;
            while (true) {
                // copy and pop message
                try {
                    temp = incomingQueue.take();
                } catch (InterruptedException e) {
                    System.out.println("Could not take element from incoming queue :(");
                    //e.printStackTrace();
                }
                //System.out.println("Inbox got: " + temp.senderID + ": " + temp.message);
                already_exists = false;
                if (processingTable.isEmpty()) {
                    processingTable.add(new ArrayList<>());
                    processingTable.get(0).add(temp);
                } else {
                    for (int i = 0; i < processingTable.size(); i++) {
                        if (processingTable.get(i).get(0).lamp_clock == temp.lamp_clock) {
                            processingTable.get(i).add(temp);
                            already_exists = true;

                            compareSet = new HashSet<>();
                            // check whether this new message is the "missing message"
                            for (int j = 0; j < processingTable.get(i).size(); j++) {
                                compareSet.add(processingTable.get(i).get(j).senderID);
                            }

                            // add to history
                            // Check whether all peers received and forwarded the message
                            if (compareSet.equals(knownPeers)) {
                                // check if there's a smaller lamport clock?
                                // Not necessary, because the list already goes from smaller to larger.
                                if (lampClock.get() <= processingTable.get(i).get(0).lamp_clock){
                                    lampClock.set(processingTable.get(i).get(0).lamp_clock);
                                    lampClock.incrementAndGet();
                                }

                                System.out.println("Now I can pop this message!");
                                for (message m : processingTable.get(i)) {
                                    System.out.println("Forwarded by " + m.senderID + "@" + m.lamp_clock + " OC: " + m.originalSender + ": " + m.message);
                                }

                                history.add(processingTable.get(i).get(0));
                                processingTable.remove(i);
                            }
                            break;
                        }
                    }
                    if (!already_exists) {
                        processingTable.add(new ArrayList<message>());
                        processingTable.get(processingTable.size() - 1).add(temp);
                    }
                }

                for (List<message> i : processingTable) {
                    for (message j : i) {
                        //System.out.println("From " + j.senderID + "@" + j.lamp_clock + ": " + j.message);
                    }
                }
            }
        }
    }

    static class outgoingBox implements Runnable {

        @Override
        public void run() {
            chatMessage message;
            chatMessage acknowledgement;
            message buffer = new message(0, "ola", "eu", ":b");

            while (true) {
                // make (gRPC )message
                try {
                    buffer = outgoingQueue.take();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                //System.out.println(buffer.senderID + ": " + buffer.message);
                message = chatMessage.newBuilder()
                        .setLampClock(buffer.lamp_clock)
                        .setContent(buffer.message)
                        .setSenderId(buffer.senderID)
                        .setOriginalSender(buffer.originalSender)
                        .build();
                // Remove first element
                outgoingQueue.remove(0);
                // send message
                for (int i = 0; i < connections.size(); i++) {
                    acknowledgement = connections.get(i).send(message);
                }
            }
        }
    }

}
