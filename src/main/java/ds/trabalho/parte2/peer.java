package ds.trabalho.parte2;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;

import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.TimeUnit;

// Read CSV
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import java.io.FileReader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static java.lang.Math.abs;

import java.net.NetworkInterface;
import java.net.InetAddress;

public class peer {
    static List<String> knownPeers;
    static List<String> srcDictionary;
    static Set<String> sharedDictionary;
    static int port;

    static AtomicReference<String> myIpPort;

    public peer() throws IOException, CsvException {
        knownPeers = Collections.synchronizedList(new Vector<>());
        myIpPort = new AtomicReference<>();
        sharedDictionary = Collections.synchronizedSet(new HashSet<>());

        CSVReader reader = new CSVReader(new FileReader("dictionary-keysonly.csv"));
        List<String[]> myEntries = reader.readAll();
        srcDictionary = myEntries.stream()
                .map(x -> x[0])
                .collect(Collectors.toList());
    }

    /**
     * Start the client
     */
    public static void main(String[] args) throws Exception {
        // String target = "localhost:50051";
        Scanner scanner = new Scanner(System.in);

        System.out.printf("Choose a port to start the server: ");
        port = Integer.parseInt(scanner.nextLine());

        peer p = new peer();

        try {
            myIpPort.set(InetAddress.getLocalHost().getHostAddress() + ":" + port);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        System.out.printf("New peer @ host=%s\n", port);
        new Thread(new dictionary_keeper(7)).start();
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

        static Server server;
        static dictionary dictionary_message;
        static target target_message;
        static addresses addresses_message;

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

        static class p2pImpl extends p2pGrpc.p2pImplBase {

            @Override
            public void register(addresses request, StreamObserver<addresses> responseObserver) {
                addresses_message = addresses.newBuilder().addAllMessage(knownPeers).build();

                Set<String> temp = new HashSet<>(request.getMessageList());
                temp.removeAll(knownPeers);
                knownPeers.addAll(temp);

                responseObserver.onNext(addresses_message);
                responseObserver.onCompleted();
            }

            @Override
            public void push(dictionary request, StreamObserver<target> responseObserver) {
                // Receive client's dictionary
                sharedDictionary.addAll(request.getMessageList());
                target_message = (target.newBuilder()).setName("Ok! :)").build();
                responseObserver.onNext(target_message);
                responseObserver.onCompleted();
            }

            @Override
            public void pull(target request, StreamObserver<dictionary> responseObserver) {
                dictionary_message = dictionary.newBuilder().addAllMessage(sharedDictionary).build();
                responseObserver.onNext(dictionary_message);
                responseObserver.onCompleted();
            }

            @Override
            public void pushpull(dictionary request, StreamObserver<dictionary> responseObserver) {
                dictionary_message = dictionary.newBuilder().addAllMessage(sharedDictionary).build();
                sharedDictionary.addAll(request.getMessageList());
                responseObserver.onNext(dictionary_message);
                responseObserver.onCompleted();
            }
        }
    }

    static class p2pClient implements Runnable {
        //public p2pClient() {        }

        @Override
        public void run() {
            Scanner scanner = new Scanner(System.in);;
            String server_port;

            System.out.printf("Write your command as 'command argument'\n");

            ManagedChannel m_channel;
            p2pGrpc.p2pBlockingStub blockingStub;

            dictionary dictionary_message;
            dictionary dictionary_reply;
            target target_message;
            target target_reply;
            addresses addresses_message;
            addresses addresses_reply;

            knownPeers.add(String.valueOf(myIpPort));

            p2pClient client = new p2pClient();
            String command = "0";
            String argument;
            while (!command.equals("quit")) {

                System.out.printf("$ ");

                command = scanner.next();
                argument = scanner.next();
                target_message = target.newBuilder().setName(argument).build();

                if (command.equals("get")) {
                    switch (argument) {
                        case "dictionary":
                            System.out.println(sharedDictionary);
                            break;
                        case "peers":
                            System.out.println(knownPeers);
                            break;
                    }
                }
                else{
                    if (command.equals("register")){
                        try {
                            m_channel = ManagedChannelBuilder.forTarget(argument)
                                    .usePlaintext()
                                    .build();
                            blockingStub = p2pGrpc.newBlockingStub(m_channel);

                            addresses_message = addresses.newBuilder().addAllMessage(knownPeers).build();
                            addresses_reply = blockingStub.register(addresses_message);

                            Set<String> temp = new HashSet<>(addresses_reply.getMessageList());
                            temp.removeAll(knownPeers);
                            knownPeers.addAll(temp);
                        } catch (Exception e) {
                            System.out.println("Could not fulfill request with peer. Potentially invalid peer.");
                            //e.printStackTrace();
                        }
                    }
                    else {
                        // "use index instead of peer name"
                        int peer = Integer.parseInt(argument);
                        m_channel = ManagedChannelBuilder.forTarget(knownPeers.get(peer))
                                .usePlaintext()
                                .build();
                        blockingStub = p2pGrpc.newBlockingStub(m_channel);
                        switch (command) {
                            case "push":
                                // send its own dictionary
                                dictionary_message = dictionary.newBuilder().addAllMessage(sharedDictionary).build();
                                target_reply = blockingStub.push(dictionary_message);
                                break;
                            case "pull":
                                // get target's dictionary
                                dictionary_reply = blockingStub.pull(target_message);
                                sharedDictionary.addAll(dictionary_reply.getMessageList());
                                break;
                            case "pushpull":
                                dictionary_message = dictionary.newBuilder().addAllMessage(sharedDictionary).build();
                                dictionary_reply = blockingStub.pushpull(dictionary_message);
                                sharedDictionary.addAll(dictionary_reply.getMessageList());
                                break;
                        }
                    }
                }
            }
        }
    }

    static class dictionary_keeper implements Runnable {
        // Implement simple class to add entries to the dictionary
        int rate;
        Random random_index = new Random();

        public dictionary_keeper(int x) {
            rate = x;
        }

        @Override
        public void run() {
            int index;
            //List<String> aux;
            while (true) {
                index = abs(random_index.nextInt()) % srcDictionary.size();
                sharedDictionary.add(srcDictionary.get(index));
                // remove duplicates. This way it's breaking the references.
                // System.out.println(shared_dictionary);
                //aux = new Vector<>((new HashSet<>(shared_dictionary)));
                //shared_dictionary = Collections.synchronizedList(aux);
                try {
                    TimeUnit.SECONDS.sleep(rate);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}