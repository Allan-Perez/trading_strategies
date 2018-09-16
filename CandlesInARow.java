import java.util.*;
import com.dukascopy.api.*;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.concurrent.CopyOnWriteArrayList;
import java.lang.reflect.*;
import java.math.BigDecimal;


public class CandlesInARow implements IStrategy{
	private CopyOnWriteArrayList<TradeEventAction> tradeEventActions = new CopyOnWriteArrayList<TradeEventAction>();
	private static final String DATE_FORMAT_NOW = "yyyyMMdd_HHmmss";
	private IEngine engine;
	private IConsole console;
	private IHistory history;
	private IContext context;
	private IIndicators indicators;
	private IUserInterface userInterface;

	@Configurable("defaultTakeProfit:")
	public int defaultTakeProfit = 40;
	@Configurable("defaultInstrument:")
	public Instrument defaultInstrument = Instrument.EURUSD;
	@Configurable("defaultSlippage:")
	public int defaultSlippage = 5;
	@Configurable("defaultStopLoss:")
	public int defaultStopLoss = 20;
	@Configurable("defaultPeriod:")
	public Period defaultPeriod = Period.ONE_MIN;

	private String AccountId = "";
	private String AccountCurrency = "";
	private double Equity;    
	private double Leverage;
	private double UseofLeverage;
	private int OverWeekendEndLeverage;
	private int MarginCutLevel;
	private IMessage LastTradeEvent =  null ;
	private Candle LastAskCandle =  null ;
	private Candle LastBidCandle =  null ;
	private List<IOrder> AllPositions =  null ;
	private List<IOrder> PendingPositions =  null ;
	private List<IOrder> OpenPositions =  null ;
	private boolean GlobalAccount;

	public void onStart(IContext context) throws JFException {
		this.context = context;
		this.engine = context.getEngine();
		this.console = context.getConsole();
		this.history = context.getHistory();
		this.indicators = context.getIndicators();
		this.userInterface = context.getUserInterface();

		IBar bidBar = context.getHistory().getBar(defaultInstrument, 
			defaultPeriod, OfferSide.BID, 1);
		IBar askBar = context.getHistory().getBar(defaultInstrument, 
			defaultPeriod, OfferSide.ASK, 1);
		LastAskCandle = new Candle(askBar, defaultPeriod, defaultInstrument, 
			OfferSide.ASK);
		LastBidCandle = new Candle(bidBar, defaultPeriod, defaultInstrument, 
			OfferSide.BID);
	}

	public void onAccount(IAccount account) throws JFException {
		AccountCurrency = account.getCurrency().toString();
		Leverage = account.getLeverage();
		AccountId= account.getAccountId();
		Equity = account.getEquity();
		UseofLeverage = account.getUseOfLeverage();
		OverWeekendEndLeverage = account.getOverWeekEndLeverage();
		MarginCutLevel = account.getMarginCutLevel();
		GlobalAccount = account.isGlobal();
	}

    private void updateVariables(Instrument instrument) {
        try {
            AllPositions = engine.getOrders();
            List<IOrder> listMarket = new ArrayList<IOrder>();
            for (IOrder order: AllPositions) {
                if (order.getState().equals(IOrder.State.FILLED)){
                    listMarket.add(order);
                }
            }
            List<IOrder> listPending = new ArrayList<IOrder>();
            for (IOrder order: AllPositions) {
                if (order.getState().equals(IOrder.State.OPENED)){
                    listPending.add(order);
                }
            }
            OpenPositions = listMarket;
            PendingPositions = listPending;
        } catch(JFException e) {
            e.printStackTrace();
        }
    }

    public void onMessage(IMessage message) throws JFException {
        if (message.getOrder() != null) {
            updateVariables(message.getOrder().getInstrument());
            LastTradeEvent = message;
            for (TradeEventAction event :  tradeEventActions) {
                IOrder order = message.getOrder();
                if (order != null && event != null && 
                    message.getType().equals(event.getMessageType())&& 
                    order.getLabel().equals(event.getPositionLabel())) {

                    Method method;
                    try {
                        method = this.getClass().getDeclaredMethod(
                            event.getNextBlockId(), Integer.class);
                        method.invoke(this, new Integer[] {event.getFlowId()});
                    } catch (SecurityException e) {
                        e.printStackTrace();
                    } catch (NoSuchMethodException e) {
                        e.printStackTrace();
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    } catch (InvocationTargetException e) {
                        e.printStackTrace();
                    } 
                    tradeEventActions.remove(event); 
                }
            }   
        }
    }
}