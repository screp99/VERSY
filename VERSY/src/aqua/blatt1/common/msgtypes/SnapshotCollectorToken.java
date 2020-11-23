package aqua.blatt1.common.msgtypes;

import java.io.Serializable;


@SuppressWarnings("serial")
public class SnapshotCollectorToken implements Serializable {
	private int numberOfFishies = 0;

	
	public void addNumberOfFishies(int numberOfFishies) {
		this.numberOfFishies += numberOfFishies;
	}
	
	public int getNumberOfFishies() {
		return this.numberOfFishies;
	}
}
