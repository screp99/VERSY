package aqua.blatt1.client;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Observable;
import java.util.Random;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import aqua.blatt1.common.Direction;
import aqua.blatt1.common.FishModel;
import aqua.blatt1.common.msgtypes.SnapshotCollectorToken;

enum RecordingMode {
	IDLE, LEFT, RIGHT, BOTH
}

@SuppressWarnings("deprecation")
public class TankModel extends Observable implements Iterable<FishModel> {

	public static final int WIDTH = 600;
	public static final int HEIGHT = 350;
	protected static final int MAX_FISHIES = 5;
	protected static final Random rand = new Random();
	protected volatile String id;
	protected final Set<FishModel> fishies;
	protected int fishCounter = 0;
	protected final ClientCommunicator.ClientForwarder forwarder;
	protected InetSocketAddress leftNeighbor;
	protected InetSocketAddress rightNeighbor;
	protected Timer timer = new Timer();
	private volatile Boolean hasToken = false;
	private volatile Boolean isInitiator = false;
	private volatile SnapshotCollectorToken collector;
	private volatile int currentNumberOfFishies = 0;
	protected volatile Integer snapshot = 0;
	private volatile RecordingMode recordingMode = RecordingMode.IDLE;
	protected volatile List<FishModel> stateOfRight = new ArrayList<FishModel>();
	protected volatile List<FishModel> stateOfLeft = new ArrayList<FishModel>();

	public TankModel(ClientCommunicator.ClientForwarder forwarder) {
		this.fishies = Collections.newSetFromMap(new ConcurrentHashMap<FishModel, Boolean>());
		this.forwarder = forwarder;
	}

	synchronized void onRegistration(String id) {
		this.id = id;
		newFish(WIDTH - FishModel.getXSize(), rand.nextInt(HEIGHT - FishModel.getYSize()));
	}

	public synchronized void newFish(int x, int y) {
		if (fishies.size() < MAX_FISHIES) {
			x = x > WIDTH - FishModel.getXSize() - 1 ? WIDTH - FishModel.getXSize() - 1 : x;
			y = y > HEIGHT - FishModel.getYSize() ? HEIGHT - FishModel.getYSize() : y;

			FishModel fish = new FishModel("fish" + (++fishCounter) + "@" + getId(), x, y,
					rand.nextBoolean() ? Direction.LEFT : Direction.RIGHT);

			fishies.add(fish);
		}
	}

	synchronized void receiveFish(FishModel fish) {
		fish.setToStart();
		fishies.add(fish);
		if (this.recordingMode != RecordingMode.IDLE) {
			if (this.recordingMode != RecordingMode.RIGHT && fish.getDirection() == Direction.RIGHT) {
				this.stateOfLeft.add(fish);
			} else if (this.recordingMode != RecordingMode.LEFT && fish.getDirection() == Direction.LEFT) {
				this.stateOfRight.add(fish);
			}
		}
	}

	public String getId() {
		return id;
	}

	public synchronized int getFishCounter() {
		return fishCounter;
	}

	public synchronized Iterator<FishModel> iterator() {
		return fishies.iterator();
	}

	private synchronized void updateFishies() {
		for (Iterator<FishModel> it = iterator(); it.hasNext();) {
			FishModel fish = it.next();

			fish.update();

			if (fish.hitsEdge()) {
				forwarder.handOffFish(fish, this);
				this.currentNumberOfFishies--;
			}

			if (fish.disappears())
				it.remove();
		}
	}

	private synchronized void update() {
		updateFishies();
		setChanged();
		notifyObservers();
	}

	protected void run() {
		forwarder.register();

		try {
			while (!Thread.currentThread().isInterrupted()) {
				update();
				TimeUnit.MILLISECONDS.sleep(10);
			}
		} catch (InterruptedException consumed) {
			// allow method to terminate
		}
	}

	public synchronized void finish() {
		forwarder.deregister(id);
	}

	protected synchronized void recieveToken() {
		this.hasToken = true;
		TankModel tankModel = this;
		this.timer.schedule(new TimerTask() {

			@Override
			public void run() {
				tankModel.hasToken = false;
				forwarder.handOffToken(tankModel);
			}
		}, 2000);
	}

	protected synchronized Boolean hasToken() {
		return this.hasToken;
	}

	protected synchronized void initiateSnapshot() {
		this.isInitiator = true;
		startFishCounter();
		this.recordingMode = RecordingMode.BOTH;
		forwarder.mark(this);
		this.collector = new SnapshotCollectorToken();
	}

	synchronized void recieveMarkerFromLeft() {
		if (this.recordingMode == RecordingMode.IDLE) {
			startFishCounter();
			this.recordingMode = RecordingMode.RIGHT;
			forwarder.mark(this);
		} else {
			if (this.recordingMode == RecordingMode.BOTH) {
				this.recordingMode = RecordingMode.RIGHT;
			} else {
				this.recordingMode = RecordingMode.IDLE;
				this.currentNumberOfFishies += this.stateOfLeft.size() + this.stateOfRight.size();
				if (this.collector != null) {
					handOffCollector();
				}
			}
		}
	}

	private synchronized void handOffCollector() {
		this.collector.addNumberOfFishies(this.currentNumberOfFishies);
		forwarder.handOffCollector(this, collector);
		this.currentNumberOfFishies = 0;
		this.stateOfLeft = new ArrayList<FishModel>();
		this.stateOfRight = new ArrayList<FishModel>();
		this.collector = null;
	}

	synchronized void recieveMarkerFromRight() {
		if (this.recordingMode == RecordingMode.IDLE) {
			startFishCounter();
			this.recordingMode = RecordingMode.LEFT;
			forwarder.mark(this);
		} else {
			if (this.recordingMode == RecordingMode.BOTH) {
				this.recordingMode = RecordingMode.LEFT;
			} else {
				this.recordingMode = RecordingMode.IDLE;
				this.currentNumberOfFishies += this.stateOfLeft.size() + this.stateOfRight.size();
			}
		}
	}

	private synchronized void startFishCounter() {
		Iterator<FishModel> it = iterator();
		currentNumberOfFishies = 0;
		while (it.hasNext()) {
			it.next();
			currentNumberOfFishies++;
		}
	}

	public synchronized void recieveCollector(SnapshotCollectorToken collector) {
		this.collector = collector;
		if (this.isInitiator == true) {
			this.isInitiator = false;
			this.snapshot = collector.getNumberOfFishies();
		} else if (this.recordingMode == RecordingMode.IDLE) {
			handOffCollector();
		}
	}

}