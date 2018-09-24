import java.util.*;
import com.dukascopy.api.*;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.concurrent.CopyOnWriteArrayList;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.Period;


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
    @Configurable("Take profit: ")
    public int defaultTakeProfit = 50; // 70 goes well
    @Configurable("defaultInstrument:")
    public Instrument defaultInstrument = Instrument.EURUSD;
    @Configurable("defaultSlippage:")
    public int defaultSlippage = 5;
    @Configurable("defaultStopLoss:")
    public int defaultStopLoss = 30; // 30 goes well
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

    //Indicator
    private double _sar;
    @Configurable("Period SAR: ")
    public com.dukascopy.api.Period _sar_period = com.dukascopy.api.Period.FOUR_HOURS;
    private int _sar_shift = 0;
    @Configurable("SAR ACC: ")
    public double _sar_acc = 0.02;
    @Configurable("SAR MAX: ")
    public double _sar_max = 0.2;
    
    private double _hma;
    @Configurable("HMA Time Period: ")
    public int _hma_time_period = 6;
    @Configurable("HMA Period")
    public com.dukascopy.api.Period _hma_period = com.dukascopy.api.Period.FOUR_HOURS;
    private int _hma_shift = 0;
    

    private double _hma_t1;
    private double _sar_t1;

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
    public double _max_lot = 8.0; 
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

        if (indicators.getIndicator("SAR") == null) 
                indicators.registerDownloadableIndicator("1291","SAR");
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

    public void onBar(Instrument instrument, com.dukascopy.api.Period period, 
        IBar askBar, IBar bidBar) throws JFException {

        LastAskCandle = new Candle(askBar, period, instrument, OfferSide.ASK);
        LastBidCandle = new Candle(bidBar, period, instrument, OfferSide.BID);
        updateVariables(instrument);
        if(validBar(instrument, period)){
            createIndicators();
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

    private boolean validBar(Instrument instrument, com.dukascopy.api.Period period){
        return (instrument != null && instrument.equals(defaultInstrument)) &&
            (period != null && period.equals(defaultPeriod));
    }

    private void strategy(){
        updateCandlesCount();
        if(anyOpenOperations())
            updateTrailingStop(_last_position);
        else 
            checkOperationChance();

        _hma_t1 = _hma;
        _sar_t1 = _sar;
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
        double diff_hma_sar = _sar - _hma;
        double diff_hma_sar_t1 = _sar_t1 - _hma_t1;
        if(bullishEvent() && LastBidCandle.getClose() > _sar && 
            diff_hma_sar < diff_hma_sar_t1){
            MarketOrder("BUY");
        }
        else if(bearishEvent() && LastAskCandle.getClose() < _sar && 
            diff_hma_sar > diff_hma_sar_t1){
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
                
                stopLoss =  (order.isLong()) ? 
                round(order.getOpenPrice() - order.getInstrument().getPipValue() * defaultStopLoss, order.getInstrument()): 
                round(order.getOpenPrice() + order.getInstrument().getPipValue() * defaultStopLoss, order.getInstrument());
                
                if ((stopLoss != order.getStopLossPrice()) && (stopLoss != 0)
                    && (order.getState().equals(IOrder.State.OPENED) ||
                        order.getState().equals(IOrder.State.FILLED))) 

                    order.setStopLossPrice(stopLoss, OfferSide.BID, _pips_trailing_step);
                
                console.getOut().println("Updated stop loss with t_step: " + _pips_trailing_step);

                TradeEventAction event = new TradeEventAction();
                event.setMessageType(IMessage.Type.ORDER_CHANGED_OK);
                event.setPositionLabel(order.getLabel());
                tradeEventActions.add(event);
            } catch (JFException e) {
                e.printStackTrace();
            }
        }

        CheckFreakingTime(order);
        
    }

    private void CheckFreakingTime(IOrder order){
        Date cT = new Date(order.getCreationTime());
        Calendar creationTime = Calendar.getInstance();
        creationTime.setTime(cT);
        Calendar nowTime = Calendar.getInstance();
        nowTime.setTime(new Date(LastTick.getTime()));
        
        java.time.LocalDateTime creationDateTime = java.time.LocalDateTime.of(
            creationTime.get(Calendar.YEAR),
            creationTime.get(Calendar.MONTH),
            creationTime.get(Calendar.DAY_OF_MONTH),
            creationTime.get(Calendar.HOUR_OF_DAY),
            creationTime.get(Calendar.MINUTE)
            );

        java.time.LocalDateTime nowDateTime = java.time.LocalDateTime.of(
            nowTime.get(Calendar.YEAR),
            nowTime.get(Calendar.MONTH),
            nowTime.get(Calendar.DAY_OF_MONTH),
            nowTime.get(Calendar.HOUR_OF_DAY),
            nowTime.get(Calendar.MINUTE)
            );

        java.time.LocalDateTime diffDateTime = java.time.LocalDateTime.from( creationDateTime );
        long hoursDiff = diffDateTime.until(nowDateTime, java.time.temporal.ChronoUnit.HOURS);

        if(hoursDiff >= 24){
            this._last_position = order;
            ClosePosition();
        }
    }

    private void CloseAllPositons(){
        for (IOrder position : OpenPositions){
            if (position.getState() == IOrder.State.OPENED ||
                position.getState() == IOrder.State.FILLED)
                _last_position = position;
            ClosePosition();
        }
    }

    private void ClosePosition(){
        try {
            if (_last_position != null && (_last_position.getState() == IOrder.State.OPENED ||
                _last_position.getState() == IOrder.State.FILLED))
                
                _last_position.close();
            
        } catch (JFException e)  {
            e.printStackTrace();
        }
    }

    private void createIndicators(){
        createSAR();
        createHMA();
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
            long time = context.getHistory().getBar(defaultInstrument, _sar_period, OfferSide.BID, _sar_shift).getTime();
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

    private void createHMA(){
        OfferSide[] offerside = new OfferSide[1];
        IIndicators.AppliedPrice[] appliedPrice = new IIndicators.AppliedPrice[1];
        offerside[0] = OfferSide.BID;
        appliedPrice[0] = IIndicators.AppliedPrice.CLOSE;
        Object[] params = new Object[1];
        params[0] = _hma_time_period;
        try {
            subscriptionInstrumentCheck(defaultInstrument);
            long time = context.getHistory().getBar(defaultInstrument, _hma_period, OfferSide.BID, _hma_shift).getTime();
            Object[] indicatorResult = context.getIndicators().calculateIndicator(defaultInstrument, _hma_period, offerside,
                    "HMA", appliedPrice, params, Filter.WEEKENDS, 1, time, 0);
            if ((new Double(((double [])indicatorResult[0])[0])) == null) {
                this._hma = Double.NaN;
            } else { 
                this._hma = (((double [])indicatorResult[0])[0]);
            } 
        } catch (JFException e) {
            e.printStackTrace();
            console.getErr().println(e);
            this._hma = Double.NaN;
        }
    }

    class Candle  {

        IBar bar;
        com.dukascopy.api.Period period;
        Instrument instrument;
        OfferSide offerSide;

        public Candle(IBar bar, com.dukascopy.api.Period period, Instrument instrument, OfferSide offerSide) {
            this.bar = bar;
            this.period = period;
            this.instrument = instrument;
            this.offerSide = offerSide;
        }

        public com.dukascopy.api.Period getPeriod() {
            return period;
        }

        public void setPeriod(com.dukascopy.api.Period period) {
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