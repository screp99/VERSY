package aqua.blatt1.broker;

import java.net.InetSocketAddress;
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.swing.JOptionPane;

import aqua.blatt1.common.Direction;
import aqua.blatt1.common.Properties;
import aqua.blatt1.common.msgtypes.DeregisterRequest;
import aqua.blatt1.common.msgtypes.HandoffRequest;
import aqua.blatt1.common.msgtypes.NameResolutionRequest;
import aqua.blatt1.common.msgtypes.NameResolutionResponse;
import aqua.blatt1.common.msgtypes.NeighborUpdate;
import aqua.blatt1.common.msgtypes.RegisterRequest;
import aqua.blatt1.common.msgtypes.RegisterResponse;
import aqua.blatt1.common.msgtypes.Token;
import aqua.blatt2.broker.PoisonPill;
import messaging.Endpoint;
import messaging.Message;

public class Broker {
	private Endpoint endpoint;
	private volatile Thread killThread;
	private volatile ClientCollection<InetSocketAddress> collection;
	private volatile boolean runningFlag;
	private final int LEASE_TIME_MILLIS = 10000;

	public Broker() {
		this.endpoint = new Endpoint(Properties.PORT);
		this.collection = new ClientCollection<>();
		this.runningFlag = true;
		this.killThread = new Thread(new Runnable() {
			@Override
			public void run() {
				JOptionPane.showMessageDialog(null, "Press OK button to stop server");
				runningFlag = false;
			}
		});
		this.killThread.start();
	}

	public void broker() {
		ExecutorService executor = Executors.newFixedThreadPool(10);

		new Timer(true).schedule(new TimerTask() {
			@Override
			public void run() {
				for (int i = 0; i < Broker.this.collection.size(); i++) {
					Calendar leaseEndTimestamp = (Calendar) Broker.this.collection.getLeaseTimeStamp(i).clone();
					leaseEndTimestamp.add(Calendar.MILLISECOND, LEASE_TIME_MILLIS);
					if (Calendar.getInstance().after(leaseEndTimestamp)) {
						executor.execute(
								new BrokerTask(new Message(new DeregisterRequest(Broker.this.collection.getClientId(i)),
										Broker.this.collection.getClient(i))));
					}
				}
			}
		}, 5000, 5000);

		while (this.runningFlag) {
			Message msg = this.endpoint.nonBlockingReceive();
			if (msg == null) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				continue;
			}
			if (msg.getPayload() instanceof PoisonPill)
				break;
			executor.execute(new BrokerTask(msg));
		}
		executor.shutdown();

		if (this.killThread.isAlive()) {
			this.killThread.interrupt();
		}

		System.out.println("Broker stopped");
	}

	public static void main(String[] args) {
		Broker broker = new Broker();
		broker.broker();
	}

	public class BrokerTask implements Runnable {
		Message message;
		ReadWriteLock lock;

		private BrokerTask(Message msg) {
			this.message = msg;
			this.lock = new ReentrantReadWriteLock();
		}

		@Override
		public void run() {
			var payload = this.message.getPayload();

			if (payload instanceof RegisterRequest) {
				register(this.message.getSender());
			} else if (payload instanceof DeregisterRequest) {
				DeregisterRequest dr = (DeregisterRequest) payload;
				deregister(dr.getId());
			} else if (payload instanceof HandoffRequest) {
				System.out.println("HandOf received");
				HandoffRequest hor = (HandoffRequest) payload;
				handoffFish(hor, this.message.getSender());
			} else if (payload instanceof NameResolutionRequest) {
				NameResolutionRequest req = (NameResolutionRequest) payload;
				int tankIndex;
				if ((tankIndex = Broker.this.collection.indexOf(req.getTankId())) > 0) {
					InetSocketAddress addr = Broker.this.collection.getClient(tankIndex);
					Broker.this.endpoint.send(this.message.getSender(),
							new NameResolutionResponse(addr, req.getRequestId()));
				}
			}
		}

		private synchronized void register(InetSocketAddress addr) {
			if (Broker.this.collection.containsClient(addr)) {
				int clientIndex = Broker.this.collection.indexOf(addr);
				Broker.this.collection.setLeaseTimeStamp(clientIndex, Calendar.getInstance());
				Broker.this.endpoint.send(addr,
						new RegisterResponse(Broker.this.collection.getClientId(clientIndex), LEASE_TIME_MILLIS));
				return;
			}

			String id = "tank" + (Broker.this.collection.size() + 1);
			Broker.this.collection.add(id, Calendar.getInstance(), addr);

			if (Broker.this.collection.size() == 1) {
				Broker.this.endpoint.send(addr, new Token());
			}

			int index = Broker.this.collection.indexOf(id);
			InetSocketAddress rightNeighbor = Broker.this.collection.getRightNeighorOf(index);
			InetSocketAddress leftNeighbor = Broker.this.collection.getLeftNeighorOf(index);

			Broker.this.endpoint.send(leftNeighbor, new NeighborUpdate(null, addr));
			Broker.this.endpoint.send(rightNeighbor, new NeighborUpdate(addr, null));
			Broker.this.endpoint.send(addr, new NeighborUpdate(leftNeighbor, rightNeighbor));
			Broker.this.endpoint.send(addr, new RegisterResponse(id, LEASE_TIME_MILLIS));
		}

		private synchronized void deregister(String id) {
			int index = Broker.this.collection.indexOf(id);
			InetSocketAddress rightNeighbor = Broker.this.collection.getRightNeighorOf(index);
			InetSocketAddress leftNeighbor = Broker.this.collection.getLeftNeighorOf(index);

			Broker.this.endpoint.send(leftNeighbor, new NeighborUpdate(null, rightNeighbor));
			Broker.this.endpoint.send(rightNeighbor, new NeighborUpdate(leftNeighbor, null));

			Broker.this.collection.remove(Broker.this.collection.indexOf(id));
		}

		private void handoffFish(HandoffRequest hor, InetSocketAddress addr) {
			this.lock.readLock().lock();
			int currentFishTankIndex = Broker.this.collection.indexOf(addr);
			this.lock.readLock().unlock();

			InetSocketAddress nextClient;
			if (hor.getFish().getDirection() == Direction.LEFT) {
				this.lock.readLock().lock();
				nextClient = Broker.this.collection.getLeftNeighorOf(currentFishTankIndex);
				this.lock.readLock().unlock();
			} else {
				this.lock.readLock().lock();
				nextClient = Broker.this.collection.getRightNeighorOf(currentFishTankIndex);
				this.lock.readLock().unlock();
			}

			Broker.this.endpoint.send(nextClient, hor);
		}
	}
}
