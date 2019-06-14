package communication;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.temporal.ChronoField;
import java.util.concurrent.ArrayBlockingQueue;
import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.scene.control.Label;

/**
 * Server that can send and receive UDP messages. There are 2
 * Threads, one for sending and one for receiving. Each Thread uses it's own
 * socket set up on different ports.
 *
 * @author brian.mahoney
 */
public class CommunicationManager
{

    private boolean stopped = true;
    private volatile Instant lastReceivedTime;
    private volatile int messageCount = 0;    
    private SimpleIntegerProperty messageCountProperty;
    
    private final int heartBeatInterval = 1000; // milliseconds
    
    // Receive
    private Thread receiveThread;
    private DatagramSocket receiveSocket = null;
    private final int receivePort = 49676;
    private Label lastTimeLabel;

    // Send
    private Thread sendThread;
    private DatagramSocket sendSocket = null;
    private InetAddress sendAddress = null;
    private InetAddress defaultSendAddress = null;
    private final int localSendPort = 58735;
    
    private Thread heartBeatCheckerThread;

    // FIFO queue for incoming messages
    private final ArrayBlockingQueue<HeartBeatMessage> theQueue;

    public HeartBeatServer(Label lastTimeLabel)
    {
        defaultSendAddress = InetAddress.getLoopbackAddress();
        sendAddress = defaultSendAddress;
        
        
        messageCountProperty = new SimpleIntegerProperty(messageCount);
        
        this.lastTimeLabel = lastTimeLabel;
        /*
        this.lastTimeLabel.textProperty().bind(messageCountProperty.asString());
        
        messageCountProperty.addListener(new ChangeListener()
        {
            @Override public void changed(ObservableValue o,Object oldVal, 
                 Object newVal)
            {
                System.out.println("Electric bill has changed!");
            }
        });
        */
        
        theQueue = new ArrayBlockingQueue<HeartBeatMessage>(10000000, true);
        Thread queueThread = new Thread(getConsumerRunnable());
        queueThread.setDaemon(true);
        queueThread.start();
    }

    /**
     * Gets the Runnable for sending messages
     *
     * @return an instance of Runnable that sends messages to a specific
     * host/port
     */
    private Runnable getSendRunnable(int timeBetweenMessages)
    {
        Runnable runner = new Runnable()
        {
            @Override
            public void run()
            {
                String indents = "-----";
                long count = 0;
                while (!stopped)
                {
                    try
                    {
                        Thread.sleep(timeBetweenMessages);
                        byte[] buf = new byte[1024];

                        // figure out response
                        HeartBeatMessage message = new HeartBeatMessage();
                        createPacketFromMessage(message);

                        // send the response to the client at "address" and "port"
                        DatagramPacket packet = createPacketFromMessage(message);
                        packet.setAddress(sendAddress);
                        packet.setPort(receivePort);

                        sendSocket.send(packet);
                        print(indents, "HeartBeat message sent: " + ++count);
                    } 
                    catch (IOException ex)
                    {
                        if (stopped)
                        {
                            print(indents, "Send: IOException due to stopping server: " + ex.getMessage());
                        } 
                        else
                        {
                            print(indents, "Send: IOException: " + ex.getMessage());
                        }
                    } 
                    catch (InterruptedException ex)
                    {
                        print(indents, "Thread interrupted while sleeping before heartbeat: " + ex.getMessage());
                    }
                }
                sendSocket.close();
                print(indents, "Send server stopped");
            }
        };
        return runner;
    }

    /**
     * Gets the Runnable for receiving messages
     *
     * @return an instance of Runnable that can receive messages
     */
    private Runnable getReceiveRunnable()
    {
        Runnable runner = new Runnable()
        {
            @Override
            public void run()
            {
                String indents = "*****";
                print(indents, "Accepting heartbeats...");
                while (!stopped)
                {
                    try
                    {
                        byte[] buf = new byte[1024];

                        // receive request
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        receiveSocket.receive(packet);

                        HeartBeatMessage message = getObjectFromPacket(packet);
                        theQueue.put(message);
                    }
                    catch (IOException ioe)
                    {
                        if (stopped)
                        {
                            print(indents, "Receive: IOException due to stopping receive server: " + ioe.getMessage());
                        } 
                        else
                        {
                            print(indents, "Receive: IOException: " + ioe.getMessage());
                        }
                    } 
                    catch (InterruptedException ie)
                    {
                        print(indents, "Receive: Interrupted while adding message to queue: " + ie.getMessage());
                    }
                }
                receiveSocket.close();
                print(indents, "Receive server stopped");
            }
        };
        return runner;
    }

    /**
     * Stops sending messages. This will kill the sending thread and close the
     * send socket.
     *
     * @throws IllegalStateException if the server is not running
     */
    public void stopSendServer() throws IllegalStateException
    {
        if (stopped)
        {
            throw new IllegalStateException("Send server is already stopped");
        }

        stopped = true;
        sendSocket.close();
        sendThread.interrupt();
    }

    /**
     * Starts sending messages to the designated IP Address
     *
     * @param ipAddress the address to send the messages to
     * @throws SocketException if socket cannot be created
     * @throws IllegalStateException if server is already running
     */
    public void startSendServer(String ipAddress, int timeBetweenMessages)
            throws SocketException, IllegalStateException
    {
        if (!stopped)
        {
            throw new IllegalStateException("Send server is already running");
        }

        stopped = false;
        sendSocket = new DatagramSocket(localSendPort);
        if (ipAddress.length() > 0)
        {
            try
            {
                sendAddress = InetAddress.getByName(ipAddress);
            } 
            catch (UnknownHostException ex)
            {
                print("", "Unknown host when getting sendAddress: " + ex.getMessage());
            }
        }

        print("", "Starting send server...");
        sendThread = new Thread(getSendRunnable(timeBetweenMessages));
        sendThread.setDaemon(true);
        sendThread.start();
    }

    /**
     * Stops receiving messages. This will kill the receive Thread and close the
     * receive Socket
     *
     * @throws IllegalStateException if the receive server is not running
     */
    public void stopReceiveServer() throws IllegalStateException
    {
        if (stopped)
        {
            throw new IllegalStateException("Receive server is already stopped");
        }

        stopped = true;
        receiveSocket.close();
    }

    /**
     * Starts receiving messages
     *
     * @throws SocketException if socket can not be created
     * @throws IllegalStateException if the receive server is already running
     */
    public void startReceiveServer() throws SocketException, IllegalStateException
    {
        if (!stopped)
        {
            throw new IllegalStateException("Receive Server is already running");
        }

        lastReceivedTime = Instant.now();

        stopped = false;
        receiveSocket = new DatagramSocket(receivePort);

        print("", "Starting receive server...");
        receiveThread = new Thread(getReceiveRunnable());
        receiveThread.setDaemon(true);
        receiveThread.start();
        
        heartBeatCheckerThread = new Thread(getHeartBeatCheckerRunnable());
        heartBeatCheckerThread.setDaemon(true);
        heartBeatCheckerThread.start();
    }

    /**
     * Creates a DatagramPacket from the HeartBeatMessage. The returned packet
     * still needs to have a port and address/host set before sending
     *
     * @param message the HeartBeatMessage to encode in the DatagramPacket
     * @return the encoded DatagramPacket
     */
    public DatagramPacket createPacketFromMessage(HeartBeatMessage message)
    {
        //Send objects in datagrams
        ByteArrayOutputStream baos = new ByteArrayOutputStream(6400);
        ObjectOutputStream oos;
        DatagramPacket packet = null;

        try
        {
            oos = new ObjectOutputStream(baos);
            oos.writeObject(message);
            byte[] data = baos.toByteArray();
            //print("", "Packet size: " + data.length);
            packet = new DatagramPacket(data, data.length);
        } catch (IOException ex)
        {
            print("", "Exception while creating byte stream: " + ex.getMessage());
        }

        return packet;
    }

    /**
     * Creates a HeartBeatMessage from the DatagramPacket
     *
     * @param packet the DatagramPacket encoded from a HeartBeatMessage. See
     * {@link #createPacketFromMessage(network.HeartBeatMessage)}
     * @return returns a HeartBeatMessage decoded from {
     * @paramref packet}
     */
    public HeartBeatMessage getObjectFromPacket(DatagramPacket packet)
    {
        //recieve object from packet
        byte[] data = packet.getData();
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        HeartBeatMessage message = null;

        try
        {
            ObjectInputStream ois = new ObjectInputStream(bais);
            message = (HeartBeatMessage) ois.readObject();
        } catch (IOException ex)
        {
            print("", "Exception while creating object input stream: " + ex.getMessage());
        } catch (ClassNotFoundException ex)
        {
            print("", "Exception while reading object from stream: " + ex.getMessage());
        }
        return message;
    }

    /**
     *
     * @param instant
     */
    public synchronized void updateLastReceivedTime(Instant instant)
    {
        if (instant.isAfter(lastReceivedTime))
        {
            lastReceivedTime = instant;
        }
    }

    /**
     *
     * @return
     */
    public synchronized Instant getLastReceivedTime()
    {
        return this.lastReceivedTime;
    }

    private void print(String indents, String message)
    {
        System.out.println(indents + message);
    }

    private Runnable getConsumerRunnable()
    {
        Runnable runner = new Runnable()
        {
            long count = 0;
            
            @Override
            public void run()
            {
                try
                {
                    while (!Thread.currentThread().isInterrupted())
                    {
                        print("*****", "Messages pulled from the queue: " + count);
                        
                        HeartBeatMessage message = theQueue.take();
                        lastReceivedTime = message.getPayload();
                        updateLabel();
                        messageCount += 1;
                        //messageCountProperty.setValue(messageCount);
                        count++;
                    }
                } 
                catch (InterruptedException ex)
                {
            
                }
            }
        };
        return runner;
    }
    
    private Runnable getHeartBeatCheckerRunnable()
    {
        Runnable runner = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    System.out.println("Running checker");     
                    while (!stopped)
                    {
                        Instant now = Instant.now();
                        Thread.sleep(5000);
                        if(now.isBefore(lastReceivedTime))
                        {
                            System.out.println("isBefore");     
                        }
                        else if(now.getLong(ChronoField.INSTANT_SECONDS) - 
                                lastReceivedTime.getLong(ChronoField.INSTANT_SECONDS) > 15)
                        {
                            System.out.println("not isAfter");     
                            Platform.runLater(new Runnable() 
                            {
                                @Override public void run() 
                                {
                                    System.out.println("No heartbeat received in 15 seconds");
                                    //Alert errorAlert = new Alert(AlertType.NONE, "No heartbeat received in 15 seconds", ButtonType.OK);
                                    //errorAlert.showAndWait();
                                }
                            });
                        }
                    }
                } 
                catch (InterruptedException ex)
                {
                    System.out.println("Interrupted in HeartbeatChecker Runnable");     
                }
            }
        };
        return runner;
    }
    
    private void updateLabel()
    {
        Platform.runLater(new Runnable() 
        {
            @Override public void run() 
            {
                lastTimeLabel.setText(lastReceivedTime.toString());                    
            }
        });
    }
}
