package aqua.blatt1.client;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JOptionPane;

public class SnapshotController implements ActionListener {
	private final Component parent;
	private final TankModel tank;

	public SnapshotController(Component parent, TankModel tank) {
		this.parent = parent;
		this.tank = tank;
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		JOptionPane.showMessageDialog(parent, "Started snapshot.");
		tank.initiateSnapshot();
	}
	
}
