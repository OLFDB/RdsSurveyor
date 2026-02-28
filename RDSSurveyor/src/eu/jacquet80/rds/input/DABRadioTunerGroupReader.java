package eu.jacquet80.rds.input;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.simplejavable.Adapter;
import org.simplejavable.BluetoothUUID;
import org.simplejavable.Peripheral;
import org.simplejavable.Service;

import eu.jacquet80.rds.input.group.FrequencyChangeEvent;
import eu.jacquet80.rds.input.group.GroupEvent;
import eu.jacquet80.rds.input.group.GroupReaderEvent;
import eu.jacquet80.rds.log.RealTime;

public class DABRadioTunerGroupReader extends TunerGroupReader implements Peripheral.DataCallback, Runnable {

	private boolean newGroups;
	private DabRadioData data = new DabRadioData();
	private Peripheral peripheral;
	private BluetoothUUID serviceUuidTX;
	private BluetoothUUID characteristicUuidtx;
	private BluetoothUUID serviceUuidRX;
	private BluetoothUUID characteristicUuidrx;
	public byte[] rdsdata;
	private int oldfreq = 0;
	Adapter adapter;
	List<Peripheral> peripherals;
	boolean bt_connected = false;
	boolean scanning;

	class ScanCallback implements Adapter.EventListener {
		@Override
		public void onScanStart() {
			scanning=true;
			System.out.println("Scan started.");
		}

		@Override
		public void onScanStop() {
			scanning=false;
			System.out.println("Scan stopped.");
		}

		@Override
		public void onScanUpdated(Peripheral peripheral) {
		}

		@Override
		public void onScanFound(Peripheral peripheral) {
			System.out.println("Found device: " + peripheral.getIdentifier());
			if (peripheral.isConnectable()) {
				if (peripherals == null)
					peripherals = new ArrayList<>();
				peripherals.add(peripheral);
			}
		}
	}

	class PeripheralCallback implements Peripheral.EventListener {
		@Override
		public void onConnected() {
			System.out.println("Successfully connected, printing services and characteristics..");
			bt_connected = true;

		}

		@Override
		public void onDisconnected() {
			System.out.println("Disconnected.");
			bt_connected = false;
		}
	}

	@Override
	public void run() {
		while (true) {
			if (!bt_connected) {
				System.out.println("Searching for DABRadio device...");
				peripherals = null;
				try {
					adapter.scanFor(2000);
					adapter.scanStop();
				} catch (Exception e) {
					System.err.println("Scan failed: " + e.getMessage());
					System.exit(1);
					return;
				}

				for (int i = 0; peripherals != null && i < peripherals.size(); i++) {
					peripheral = peripherals.get(i);
//					System.out.println(
//							"[" + i + "] " + peripheral.getIdentifier() + " [" + peripheral.getAddress() + "]");
					if (peripheral.getIdentifier().equals("DAB")) {
						
						//while(scanning);
						
						System.out.println("Found DAB device");

						System.out.println("Connecting to " + peripheral.getIdentifier() + " ["
								+ peripheral.getAddress() + "] using peri: " + peripheral);
						peripheral.setEventListener(new PeripheralCallback());
						peripheral.connect();
						
						bt_connected = true;

						int rxchar = -1;
						int txchar = -1;

						List<ServiceCharacteristic> uuids = new ArrayList<>();
						for (Service service : peripheral.services()) {
							for (org.simplejavable.Characteristic characteristic : service.characteristics()) {
								uuids.add(new ServiceCharacteristic(service.uuid(), characteristic.uuid()));
							}
						}

						for (int n = 0; n < uuids.size(); n++) {
							System.out.println(
									"[" + n + "] " + uuids.get(n).serviceUuid + " " + uuids.get(n).characteristicUuid);
							if (uuids.get(n).serviceUuid.equals("6e400001-b5a3-f393-e0a9-e50e24dcca9e")) {
								System.out.println("Found NUS service");
								if (uuids.get(n).characteristicUuid.equals("6e400003-b5a3-f393-e0a9-e50e24dcca9e")) {
									System.out.println("Found NUS RX characteristic");
									rxchar = n;
								}
								if (uuids.get(n).characteristicUuid.equals("6e400002-b5a3-f393-e0a9-e50e24dcca9e")) {
									System.out.println("Found NUS TX characteristic");
									txchar = n;
								}
							}
						}

						ServiceCharacteristic selectedrx = uuids.get(rxchar);
						ServiceCharacteristic selectedtx = uuids.get(txchar);
						serviceUuidRX = new BluetoothUUID(selectedrx.serviceUuid);
						serviceUuidTX = new BluetoothUUID(selectedtx.serviceUuid);
						characteristicUuidrx = new BluetoothUUID(selectedrx.characteristicUuid);
						characteristicUuidtx = new BluetoothUUID(selectedtx.characteristicUuid);

						System.out.println("Subscribing to notification on " + characteristicUuidrx);
						peripheral.notify(serviceUuidRX, characteristicUuidrx, this);
						
						break;
					}
				}
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
			}
		}
	}

	// We use the filename as name of the BLE device
	public DABRadioTunerGroupReader(String filename) throws UnavailableInputMethod {

		try {
			
			Adapter.getAdapters();

			adapter = Adapter.getAdapters().get(0);
			System.err.println(adapter.getIdentifier());
			adapter.setEventListener(new ScanCallback());

			Thread scanner = new Thread(this);
			scanner.setName("ScanThread");
			scanner.start();

//			while (!bt_connected) {
//				try {
//					Thread.sleep(100);
//				} catch (InterruptedException e) {
//				}
//			}

		} catch (UnsatisfiedLinkError e) {
			throw new UnavailableInputMethod(filename + ": cannot use BLE device (" + e.getMessage() + ")");
		}
	}

	@Override
	public boolean isStereo() {
		return data.stereo;
	}

	@Override
	public boolean isSynchronized() {
		return data.rdsSynchronized;
	}

	@Override
	public int setFrequency(int frequency) {
		System.out.println("setFrequency: " + (frequency/10));
		if(peripheral!=null)
			peripheral.writeCommand(serviceUuidTX, characteristicUuidtx, ("tune" + (frequency/10)).getBytes());
		return 0;
	}

	@Override
	public int getFrequency() {
		return data.frequency;
	}

	@Override
	public int mute() {
		return 0;
	}

	@Override
	public int unmute() {
		return 0;
	}

	@Override
	public boolean isAudioCapable() {
		return false;
	}

	@Override
	public boolean isPlayingAudio() {
		return false;
	}

	@Override
	public int getSignalStrength() {
		// RSSI is -128-127
		int rssi = (data.rssi + 128) * 256;
		return rssi;
	}

	@Override
	public void tune(boolean up) {
		seek(up);

	}

	@Override
	public boolean seek(boolean up) {
		if (up)
			peripheral.writeCommand(serviceUuidTX, characteristicUuidtx, new byte[] { 'u', 'p' });
		else
			peripheral.writeCommand(serviceUuidTX, characteristicUuidtx, new byte[] { 'd', 'n' });
		return true;
	}

	@Override
	public String getDeviceName() {
		return "DAB";
	}

	@Override
	public boolean newGroups() {
		boolean ng = newGroups;
		newGroups = false;
		return ng;
	}

	@Override
	public GroupReaderEvent getGroup() throws IOException, EndOfStream {

		synchronized (data) {

			if (data.frequencyChanged) {
				// if frequency has just been changed, must report an event
				data.frequencyChanged = false;
				return new FrequencyChangeEvent(new RealTime(), data.frequency);
			}

			if (!data.groupReady)
				return null;

			int[] res = new int[4];
			for (int i = 0; i < 4; i++) {
				res[i] = data.blocks[i] & 0xFFFF;
			}

			newGroups = true;

			data.groupReady = false;

			return new GroupEvent(new RealTime(), res, false);
		}
	}

	private static class ServiceCharacteristic {
		final String serviceUuid;
		final String characteristicUuid;

		ServiceCharacteristic(String serviceUuid, String characteristicUuid) {
			this.serviceUuid = serviceUuid;
			this.characteristicUuid = characteristicUuid;
		}
	}

	private static String bytesToHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02X ", b));
		}
		return sb.toString().trim();
	}

	public static void main(String[] args) {
		try {
			DABRadioTunerGroupReader dab = new DABRadioTunerGroupReader("DAB");
		} catch (UnavailableInputMethod e) {
			e.printStackTrace();
		}

		while (true) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
			}
		}
	}

	@Override
	public void onDataReceived(byte[] rdsdata) {
		try {

//			System.err.println(bytesToHex(rdsdata));

			// PE 0x03 RDS data
			if (rdsdata[0] == 0x03) {
				data.blocks[0] = (short) (((rdsdata[2] & 0xFF) << 8) + (rdsdata[1] & 0xFF));
				data.blocks[1] = (short) (((rdsdata[4] & 0xFF) << 8) + (rdsdata[3] & 0xFF));
				data.blocks[2] = (short) (((rdsdata[6] & 0xFF) << 8) + (rdsdata[5] & 0xFF));
				data.blocks[3] = (short) (((rdsdata[8] & 0xFF) << 8) + (rdsdata[7] & 0xFF));

				data.groupReady = true;

			}

			// PE 0x01 RSQ status
			if (rdsdata[0] == 0x01) {
				data.frequency = (int) (((rdsdata[8] & 0xFF) << 8 | (rdsdata[7] & 0xFF)) * 10);
				data.rssi = rdsdata[10];

				if (data.frequency != oldfreq)
					data.frequencyChanged = true;

				oldfreq = data.frequency;
			}

			// PE 0x02 RDS status
			if (rdsdata[0] == 0x02) {
				data.rdsSynchronized = ((rdsdata[7] & 0x02) > 0) ? true : false;
			}

			// PE 0x00 DAB status
			if (rdsdata[0] == 0x00) {
				// FM/DAB to decide for switching to FM
				if (rdsdata[1] == 0x01) {
					peripheral.writeCommand(serviceUuidTX, characteristicUuidtx, new byte[] { 'f', 'm' });
					try {
						// Give the DAB device some time to load FM firmware and scan for first station
						Thread.sleep(4000);
					} catch (InterruptedException e) {
					}
				}
			}

			// PE 0x04 ACF status
			if (rdsdata[0] == 0x04) {
				data.stereo = (rdsdata[9] & 0x07F) > 0;
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Data read from the tuner.
	 * 
	 * Since instances of this class are shared between threads, access must be
	 * synchronized to the instance.
	 */
	private static class DabRadioData {
		private int[] blocks = { -1, -1, -1, -1 };

		/** Frequency in kHz */
		private int frequency;

		private boolean frequencyChanged = true;

		private boolean groupReady;

		private boolean rdsSynchronized;

		private boolean stereo;

		/** Received Signal Strength Indicator, 0..65535 */
		private int rssi;
	}

}
