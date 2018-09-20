import java.util.*;
import com.dukascopy.api.*;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.concurrent.CopyOnWriteArrayList;
import java.lang.reflect.*;
import java.math.BigDecimal;


public class CandlesInARow implements IStrategy{
    // Default stuff for DK API
    private CopyOnWriteArrayList<TradeEventAction> tradeEventActions = new CopyOnWriteArrayList<TradeEventAction>();
    private static final String DATE_FORMAT_NOW = "yyyyMMdd_HHmmss";
    private IEngine engine;
    private IConsole console;
    private IHistory history;
    private IContext context;
    private IIndicators indicators;
    private IUserInterface userInterface;

    // Parameter template parameters
    public int defaultTakeProfit = (int)5e4; 
    @Configurable("defaultInstrument:")
    public Instrument defaultInstrument = Instrument.EURUSD;
    @Configurable("defaultSlippage:")
    public int defaultSlippage = 5;
    @Configurable("defaultStopLoss:")
    public int defaultStopLoss = 10;
    @Configurable("defaultPeriod:")
    public Period defaultPeriod = Period.ONE_MIN;

    //Account stuff
    private String AccountId = "";
    private String AccountCurrency = "";
    private double Equity;    
    private double Leverage;
    private int MarginCutLevel;
    private double UseofLeverage;
    private boolean GlobalAccount;
    private int OverWeekendEndLeverage;
    private Tick LastTick =  null ;
    private Candle LastAskCandle = null;
    private Candle LastBidCandle = null;
    private IMessage LastTradeEvent =  null;
    private List<IOrder> AllPositions = null;
    private List<IOrder> OpenPositions = null;
    private List<IOrder> PendingPositions = null;

    // Strategy parameters
    private int _nGreenCR = 0; 
    private int _nRedCR = 0;
    @Configurable("Candles threshold: ")
    public int _nCandles = 4;

    private IOrder _last_position;
    @Configurable("Trailing step value (in pips)")
    public double _pips_trailing_step = 10; // [5,20]
    private double _lot;
    @Configurable("Maximum lot permitted:")
    public double _max_lot = 10.0; 
    private double _max_risk = 0.1;
    private double _risk_per_trade = 2e3;

    public void onStart(IContext context) throws JFException {
        this.context = context;
        this.engine = context.getEngine();
        this.console = context.getConsole();
        this.history = context.getHistory();
        this.indicators = context.getIndicators();
        this.userInterface = context.getUserInterface();

        ITick lastITick = context.getHistory().getLastTick(defaultInstrument);
        LastTick = new Tick(lastITick, defaultInstrument);
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

    public void onStop() throws JFException {
    }

    public void onTick(Instrument instrument, ITick tick) throws JFException {
        LastTick = new Tick(tick, instrument);
        updateVariables(instrument);
    }

    public void onBar(Instrument instrument, Period period, 
        IBar askBar, IBar bidBar) throws JFException {

        LastAskCandle = new Candle(askBar, period, instrument, OfferSide.ASK);
        LastBidCandle = new Candle(bidBar, period, instrument, OfferSide.BID);
        updateVariables(instrument);
        if(validBar(instrument, period)){
            strategy();
        }
    }

    public ITick getLastTick(Instrument instrument) {
        try{
            return (context.getHistory().getTick(instrument, 0));
        }catch(JFException e){
            e.printStackTrace();
        }
        return null;
    }

    public void subscriptionInstrumentCheck(Instrument instrument) {
        try {
            if (!context.getSubscribedInstruments().contains(instrument)) {
                Set<Instrument> instruments = new HashSet<Instrument>();
                instruments.add(instrument);
                context.setSubscribedInstruments(instruments, true);
                Thread.sleep(100);
            }
        }catch (InterruptedException e){
            e.printStackTrace();
        }
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

    private boolean validBar(Instrument instrument, Period period){
        return (instrument != null && instrument.equals(defaultInstrument)) &&
            (period != null && period.equals(defaultPeriod));
    }

    private void strategy(){
        updateCandlesCount();
        if(anyOpenOperations())
            updateTrailingStop(_last_position);
        else 
            checkOperationChance();
    }

    private void updateCandlesCount(){
        if(bullishCandle()){
            _nGreenCR ++;
            _nRedCR = 0;
        }
        else if(bearishCandle()){
            _nRedCR ++;
            _nGreenCR = 0;
        }
    }

    private boolean bullishCandle(){
        return LastBidCandle.getOpen() < LastBidCandle.getClose();
    }

    private boolean bearishCandle(){
        return LastBidCandle.getOpen() > LastBidCandle.getClose();
    }

    private void checkOperationChance(){
        if(bullishEvent()){
            MarketOrder("BUY");
        }
        else if(bearishEvent()){
            MarketOrder("SELL");
        }
    }

    private boolean bullishEvent(){
        return _nGreenCR == _nCandles;
    }

    private boolean bearishEvent(){
        return _nRedCR == _nCandles;
    }

    private boolean anyOpenOperations(){
        return AllPositions.size() != 0;
    }

    private void moneyManagement(){
        _lot = Equity * _max_risk/_risk_per_trade;
        _lot = (_lot > _max_lot) ? _max_lot : _lot; 
    }

    private  void MarketOrder(String dir) {
        moneyManagement();
        ITick tick = getLastTick(defaultInstrument);

        IEngine.OrderCommand command = (dir=="BUY") ? IEngine.OrderCommand.BUY :
            IEngine.OrderCommand.SELL; 

        double stopLoss =  (dir=="BUY") ? tick.getBid() - 
            defaultInstrument.getPipValue() * defaultStopLoss : tick.getBid() + 
            defaultInstrument.getPipValue() * defaultStopLoss;
        double takeProfit = (dir=="BUY") ? round(tick.getBid() + 
            defaultInstrument.getPipValue() * defaultTakeProfit, defaultInstrument):
            round(tick.getBid() - defaultInstrument.getPipValue() * 
                defaultTakeProfit, defaultInstrument);
        try {
            _last_position = context.getEngine().submitOrder(getLabel(), 
                defaultInstrument, command, _lot, 0, defaultSlippage,
                stopLoss, takeProfit, 0, "");
        } catch (JFException e) {
            e.printStackTrace();
        }
    }

    private  void updateTrailingStop(IOrder order) {
        _pips_trailing_step = (_pips_trailing_step < 10) ? 10 : _pips_trailing_step;
        if (_pips_trailing_step != order.getTrailingStep()) {
            double stopLoss;
            try {
                if (order.isLong()) {
                    stopLoss = round(order.getOpenPrice() - 
                        order.getInstrument().getPipValue() * 
                        defaultStopLoss, order.getInstrument());
                    if ((stopLoss != order.getStopLossPrice()) && (stopLoss != 0)
                        && (order.getState().equals(IOrder.State.OPENED) || 
                            order.getState().equals(IOrder.State.FILLED))) 

                        order.setStopLossPrice(stopLoss, OfferSide.BID, _pips_trailing_step);
                    
                } else if(!order.isLong()){
                    stopLoss = round(order.getOpenPrice() + 
                        order.getInstrument().getPipValue() * 
                        defaultStopLoss, order.getInstrument());
                    if ((stopLoss != order.getStopLossPrice()) && (stopLoss != 0)
                        && (order.getState().equals(IOrder.State.OPENED) ||
                            order.getState().equals(IOrder.State.FILLED))) 

                        order.setStopLossPrice(stopLoss, OfferSide.ASK, _pips_trailing_step);
                }
                TradeEventAction event = new TradeEventAction();
                event.setMessageType(IMessage.Type.ORDER_CHANGED_OK);
                event.setPositionLabel(order.getLabel());
                tradeEventActions.add(event);
            } catch (JFException e) {
                e.printStackTrace();
            }
        }
    }

    class Candle  {

        IBar bar;
        Period period;
        Instrument instrument;
        OfferSide offerSide;

        public Candle(IBar bar, Period period, Instrument instrument, OfferSide offerSide) {
            this.bar = bar;
            this.period = period;
            this.instrument = instrument;
            this.offerSide = offerSide;
        }

        public Period getPeriod() {
            return period;
        }

        public void setPeriod(Period period) {
            this.period = period;
        }

        public Instrument getInstrument() {
            return instrument;
        }

        public void setInstrument(Instrument instrument) {
            this.instrument = instrument;
        }

        public OfferSide getOfferSide() {
            return offerSide;
        }

        public void setOfferSide(OfferSide offerSide) {
            this.offerSide = offerSide;
        }

        public IBar getBar() {
            return bar;
        }

        public void setBar(IBar bar) {
            this.bar = bar;
        }

        public long getTime() {
            return bar.getTime();
        }

        public double getOpen() {
            return bar.getOpen();
        }

        public double getClose() {
            return bar.getClose();
        }

        public double getLow() {
            return bar.getLow();
        }

        public double getHigh() {
            return bar.getHigh();
        }

        public double getVolume() {
            return bar.getVolume();
        }
    }

    class Tick {
        private ITick tick;
        private Instrument instrument;

        public Tick(ITick tick, Instrument instrument){
            this.instrument = instrument;
            this.tick = tick;
        }

        public Instrument getInstrument(){
            return  instrument;
        }

        public double getAsk(){
            return  tick.getAsk();
        }

        public double getBid(){
            return  tick.getBid();
        }

        public double getAskVolume(){
            return  tick.getAskVolume();
        }

        public double getBidVolume(){
            return tick.getBidVolume();
        }

        public long getTime(){
            return  tick.getTime();
        }

        public ITick getTick(){
            return  tick;
        }
    }

    protected String getLabel() {
        String label;
        label = "IVF" + getCurrentTime(LastTick.getTime()) + generateRandom(10000) + generateRandom(10000);
        return label;
    }

    private String getCurrentTime(long time) {
        SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT_NOW);
        return sdf.format(time);
    }

    private static String generateRandom(int n) {
        int randomNumber = (int) (Math.random() * n);
        String answer = "" + randomNumber;
        if (answer.length() > 3) {
            answer = answer.substring(0, 4);
        }
        return answer;
    }

    public double round(double price, Instrument instrument) {
        BigDecimal big = new BigDecimal("" + price); 
        big = big.setScale(instrument.getPipScale() + 1, BigDecimal.ROUND_HALF_UP); 
        return big.doubleValue(); 
    }

    class TradeEventAction {
        private IMessage.Type messageType;
        private String nextBlockId = "";
        private String positionLabel = "";
        private int flowId = 0;

        public IMessage.Type getMessageType() {
            return messageType;
        }

        public void setMessageType(IMessage.Type messageType) {
            this.messageType = messageType;
        }

        public String getNextBlockId() {
            return nextBlockId;
        }

        public void setNextBlockId(String nextBlockId) {
            this.nextBlockId = nextBlockId;
        }
        public String getPositionLabel() {
            return positionLabel;
        }

        public void setPositionLabel(String positionLabel) {
            this.positionLabel = positionLabel;
        }
        public int getFlowId() {
            return flowId;
        }
        public void setFlowId(int flowId) {
            this.flowId = flowId;
        }
    }
}