package android.zyapi;


public class CommonApi {
	private static CommonApi mMe = null;
	
	public CommonApi() {
	}
	
	// gpio
	public native int setGpioMode(int pin, int mode);  
	public native int setGpioDir(int pin, int dir);
	public native int setGpioPullEnable(int pin, int enable);
	public native int setGpioPullSelect(int pin, int select);
	public native int setGpioOut(int pin, int out);
	public native int getGpioIn(int pin);
	//serialport  
	public native int openCom(String port, int baudrate, int bits, char event, int stop);
	public native int openComEx(String port, int baudrate, int bits, char event, int stop, int flags);
	public native int writeCom(int fd, byte[] buf, int sizes);
	public native int readCom(int fd, byte[] buf, int sizes);
	public native int readComEx(int fd, byte[] buf, int sizes, int sec, int usec);
	public native void closeCom(int fd);
	
	static {  
		System.loadLibrary("zyapi_common");
	}
	
}
