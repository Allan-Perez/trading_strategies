package com.dukascopy.visualforex.visualjforex;

import java.util.*;
import com.dukascopy.api.*;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.concurrent.CopyOnWriteArrayList;
import java.lang.reflect.*;
import java.math.BigDecimal;


/*
 * The name of the strategy is an acronym of indicators used:
 * MACD: MACD
 * A2: 2 ATR indicators.
 */
public class MACDA2 implements IStrategy {
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
    @Configurable("Take profit: ")
    public int defaultTakeProfit = 50; 
    @Configurable("defaultInstrument:")
    public Instrument defaultInstrument = Instrument.EURUSD;
    @Configurable("defaultSlippage:")
    public int defaultSlippage = 5;
    @Configurable("defaultStopLoss:")
    public int defaultStopLoss = 30; 
    @Configurable("defaultPeriod:")
    public com.dukascopy.api.Period defaultPeriod = com.dukascopy.api.Period.THIRTY_MINS;

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

    //Indicators 
    private double _macd; 
    private double _macds;
    private double _macdh;
    private double _atr;
    private double _shifted_atr;
    private double _sar;

    public Period _atr_period = Period.ONE_MIN;
    public int _atr_time_period = 5;
    public int _atr_shift = 0;
    public int _shifted_atr_shift = 6;

    public Period _macd_period = Period.THIRTY_MINS;
    public int _macd_fperiod = 58;
    public int _macd_slperiod = 35;
    public int _macd_siperiod = 19;

    @Configurable("SAR Period: ")
    public Period _sar_period = Period.ONE_HOUR; // FOUR_HOURS may work well 
    @Configurable("SAR acceleration: ")
    public double _sar_acc = 0.01;
    public double _sar_max = 0.2;


    //strategy parameters
    private double _lot; 
    private boolean _trailed = false;
    private IOrder _last_position;
    private double _max_risk = 0.1;
    private double _risk_per_trade = 2e3;
    public double _max_lot = 7;
    public int _pips_trailing_step = 10;



    public void onStart(IContext context) throws JFException {
        this.engine = context.getEngine();
        this.console = context.getConsole();
        this.history = context.getHistory();
        this.context = context;
        this.indicators = context.getIndicators();
        this.userInterface = context.getUserInterface();

        subscriptionInstrumentCheck(defaultInstrument);

        ITick lastITick = context.getHistory().getLastTick(defaultInstrument);
        LastTick = new Tick(lastITick, defaultInstrument);

        IBar bidBar = context.getHistory().getBar(defaultInstrument, defaultPeriod, OfferSide.BID, 1);
        IBar askBar = context.getHistory().getBar(defaultInstrument, defaultPeriod, OfferSide.ASK, 1);
        LastAskCandle = new Candle(askBar, defaultPeriod, defaultInstrument, OfferSide.ASK);
        LastBidCandle = new Candle(bidBar, defaultPeriod, defaultInstrument, OfferSide.BID);

        if (indicators.getIndicator("MACD") == null) {
            indicators.registerDownloadableIndicator("1316","MACD");
        }
        if (indicators.getIndicator("ATR") == null) {
            indicators.registerDownloadableIndicator("1305","ATR");
        }
        subscriptionInstrumentCheck(defaultInstrument);
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
        try 
        {
            AllPositions = engine.getOrders();
            List<IOrder> listMarket = new ArrayList<IOrder>();
            for (IOrder order: AllPositions) 
            {
                if (order.getState().equals(IOrder.State.FILLED))
                {
                    listMarket.add(order);
                }
            }
            List<IOrder> listPending = new ArrayList<IOrder>();
            for (IOrder order: AllPositions) 
            {
                if (order.getState().equals(IOrder.State.OPENED))
                {
                    listPending.add(order);
                }
            }
            OpenPositions = listMarket;
            PendingPositions = listPending;
        } 
        catch(JFException e) 
        {
            e.printStackTrace();
        }
    }

    public void onMessage(IMessage message) throws JFException {
        if (message.getOrder() != null) 
        {
            updateVariables(message.getOrder().getInstrument());
            LastTradeEvent = message;
            for (TradeEventAction event :  tradeEventActions) 
            {
                IOrder order = message.getOrder();
                if (order != null && event != null && message.getType().equals(event.getMessageType())&& 
                    order.getLabel().equals(event.getPositionLabel())) 
                {
                    Method method;
                    try {
                        method = this.getClass().getDeclaredMethod(event.getNextBlockId(), Integer.class);
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

    public void subscriptionInstrumentCheck(Instrument instrument) {
        try 
        {
            if (!context.getSubscribedInstruments().contains(instrument)) 
            {
                Set<Instrument> instruments = new HashSet<Instrument>();
                instruments.add(instrument);
                context.setSubscribedInstruments(instruments, true);
                Thread.sleep(100);
            }
        } catch (InterruptedException e) 
        {
            e.printStackTrace();
        }
    }

    public double round(double price, Instrument instrument) {
        BigDecimal big = new BigDecimal("" + price); 
        big = big.setScale(instrument.getPipScale() + 1, BigDecimal.ROUND_HALF_UP); 
        return big.doubleValue(); 
    }

    public ITick getLastTick(Instrument instrument) {
        try 
        { 
            return (context.getHistory().getTick(instrument, 0)); 
        } catch (JFException e) 
        { 
             e.printStackTrace();  
        } 
        return null; 
    }

    public void onTick(Instrument instrument, ITick tick) throws JFException {
        LastTick = new Tick(tick, instrument);
        updateVariables(instrument);
    }

    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        LastAskCandle = new Candle(askBar, period, instrument, OfferSide.ASK);
        LastBidCandle = new Candle(bidBar, period, instrument, OfferSide.BID);
        updateVariables(instrument);

        if(validBar(instrument, period)){
            createIndicators();
            strategy();
        }
    }

    private boolean validBar(Instrument instrument, Period period){
        return (instrument != null && instrument.equals(defaultInstrument)) &&
            (period != null && period.equals(defaultPeriod));
    }

    private void strategy(){
        if(noOpenOperations())
            openOrderStrategy();
        else 
            manageOrderStrategy();
    }

    private boolean noOpenOperations(){

        return AllPositions.size() == 0;
    }

    private void openOrderStrategy(){
        if(bullishEvent()){
            _trailed = false;
            openAtMarket("BUY");
        } else if(bearishEvent()){
            _trailed = false;
            openAtMarket("SELL");
        }
    }

    private void manageOrderStrategy(){
        if(!_trailed){
            updateTrailing(_last_position);
            _trailed = true;
        }
    }

    private boolean bullishEvent(){

        return _atr > _shifted_atr && _macdh > 0 && LastBidCandle.getClose() > _sar;
    }

    private boolean bearishEvent(){

        return _atr > _shifted_atr && _macdh < 0 && LastBidCandle.getClose() < _sar;
    }

    private void createIndicators(){
        createMACD();
        createATR();
        createShiftedATR();
        createSAR();
    }

    private void createMACD() {
        OfferSide[] offerside = new OfferSide[1];
        IIndicators.AppliedPrice[] appliedPrice = new IIndicators.AppliedPrice[1];
        offerside[0] = OfferSide.BID;
        appliedPrice[0] = IIndicators.AppliedPrice.CLOSE;
        Object[] params = new Object[3];
        params[0] = _macd_fperiod;
        params[1] = _macd_slperiod;
        params[2] = _macd_siperiod;
        try {
            subscriptionInstrumentCheck(defaultInstrument);
            long time = context.getHistory().getBar(defaultInstrument, _macd_period, OfferSide.BID, _atr_shift).getTime();
            Object[] indicatorResult = context.getIndicators().calculateIndicator(defaultInstrument, _macd_period, offerside,
                    "MACD", appliedPrice, params, Filter.WEEKENDS, 1, time, 0);
            if ((new Double(((double [])indicatorResult[0])[0])) == null) {
                this._macd = Double.NaN;
            } else { 
                this._macd = (((double [])indicatorResult[0])[0]);
            } 
            if ((new Double(((double [])indicatorResult[1])[0])) == null) {
                this._macds = Double.NaN;
            } else { 
                this._macds = (((double [])indicatorResult[1])[0]);
            } 
            if ((new Double(((double [])indicatorResult[2])[0])) == null) {
                this._macdh = Double.NaN;
            } else { 
                this._macdh = (((double [])indicatorResult[2])[0]);
            } 
        } catch (JFException e) {
            e.printStackTrace();
        }
    }

    private void createATR(){
        OfferSide[] offerside = new OfferSide[1];
        IIndicators.AppliedPrice[] appliedPrice = new IIndicators.AppliedPrice[1];
        offerside[0] = OfferSide.BID;
        appliedPrice[0] = IIndicators.AppliedPrice.CLOSE;
        Object[] params = new Object[1];
        params[0] = _atr_time_period;
        try {
            subscriptionInstrumentCheck(defaultInstrument);
            long time = context.getHistory().getBar(defaultInstrument, _atr_period, OfferSide.BID, _atr_shift).getTime();
            Object[] indicatorResult = context.getIndicators().calculateIndicator(defaultInstrument, _atr_period, offerside,
                    "ATR", appliedPrice, params, Filter.WEEKENDS, 1, time, 0);
            if ((new Double(((double [])indicatorResult[0])[0])) == null) {
                this._atr = Double.NaN;
            } else { 
                this._atr = (((double [])indicatorResult[0])[0]);
            } 
        } catch (JFException e) {
            e.printStackTrace();
        }
    }

    private void createShiftedATR(){
        OfferSide[] offerside = new OfferSide[1];
        IIndicators.AppliedPrice[] appliedPrice = new IIndicators.AppliedPrice[1];
        offerside[0] = OfferSide.BID;
        appliedPrice[0] = IIndicators.AppliedPrice.CLOSE;
        Object[] params = new Object[1];
        params[0] = _atr_time_period;
        try {
            subscriptionInstrumentCheck(defaultInstrument);
            long time = context.getHistory().getBar(defaultInstrument, _atr_period, OfferSide.BID, _shifted_atr_shift).getTime();
            Object[] indicatorResult = context.getIndicators().calculateIndicator(defaultInstrument, _atr_period, offerside,
                    "ATR", appliedPrice, params, Filter.WEEKENDS, 1, time, 0);
            if ((new Double(((double [])indicatorResult[0])[0])) == null) {
                this._shifted_atr = Double.NaN;
            } else { 
                this._shifted_atr = (((double [])indicatorResult[0])[0]);
            } 
        } catch (JFException e) {
            e.printStackTrace();
        }
    }

    private void createSAR() {
        OfferSide[] offerside = new OfferSide[1];
        IIndicators.AppliedPrice[] appliedPrice = new IIndicators.AppliedPrice[1];
        offerside[0] = OfferSide.BID;
        appliedPrice[0] = IIndicators.AppliedPrice.CLOSE;
        Object[] params = new Object[2];
        params[0] = _sar_acc;
        params[1] = _sar_max;
        try {
            subscriptionInstrumentCheck(defaultInstrument);
            long time = context.getHistory().getBar(defaultInstrument, _sar_period, OfferSide.BID, _atr_shift).getTime();
            Object[] indicatorResult = context.getIndicators().calculateIndicator(defaultInstrument, _sar_period, offerside,
                    "SAR", appliedPrice, params, Filter.WEEKENDS, 1, time, 0);
            if ((new Double(((double [])indicatorResult[0])[0])) == null) 
                this._sar = Double.NaN;
            else 
                this._sar = (((double [])indicatorResult[0])[0]);
        } catch (JFException e) {
            e.printStackTrace();
            console.getErr().println(e);
            this._sar = Double.NaN;
        }
    }

    private void moneyManagement() {
        _lot = Equity * _max_risk/_risk_per_trade;
        _lot = (_lot > _max_lot) ? _max_lot : _lot; 
    }

    private  void openAtMarket(String dir) {
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

    private void updateTrailing(IOrder order){
        _pips_trailing_step = (_pips_trailing_step < 10) ? 10 : _pips_trailing_step;
        if (_pips_trailing_step != order.getTrailingStep()) {
            double stopLoss;
            try {
                
                stopLoss =  (order.isLong()) ? 
                round(order.getOpenPrice() - order.getInstrument().getPipValue() * defaultStopLoss, order.getInstrument()): 
                round(order.getOpenPrice() + order.getInstrument().getPipValue() * (defaultStopLoss+1), order.getInstrument());
                console.getOut().println("Condition for trailing: " + stopLoss + " != " + order.getStopLossPrice());

                if ((stopLoss != order.getStopLossPrice()) && (stopLoss != 0)
                    && (order.getState().equals(IOrder.State.OPENED) ||
                        order.getState().equals(IOrder.State.FILLED))) {

                    order.setStopLossPrice(stopLoss, OfferSide.BID, _pips_trailing_step);
                    console.getOut().println("Order actually trailed is long: " + order.getOrderCommand().isLong());
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

    class Candle{

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

    class Tick{

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