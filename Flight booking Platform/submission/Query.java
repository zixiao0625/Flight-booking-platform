import java.io.FileInputStream;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Runs queries against a back-end database
 */
public class Query
{
  private String configFilename;
  private Properties configProps = new Properties();

  private String jSQLDriver;
  private String jSQLUrl;
  private String jSQLUser;
  private String jSQLPassword;

  // DB Connection
  private Connection conn;

  // Logged In User
  private String username; // customer username is unique
  
  //It result
  private ArrayList<Itinerary> itResult = new ArrayList<Itinerary>();
  
  // Canned queries

  private static final String CHECK_FLIGHT_CAPACITY = "SELECT capacity FROM Flights WHERE fid = ?";
  private PreparedStatement checkFlightCapacityStatement;

  // transactions
  private static final String BEGIN_TRANSACTION_SQL = "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE; BEGIN TRANSACTION;";
  private PreparedStatement beginTransactionStatement;

  private static final String COMMIT_SQL = "COMMIT TRANSACTION";
  private PreparedStatement commitTransactionStatement;

  private static final String ROLLBACK_SQL = "ROLLBACK TRANSACTION";
  private PreparedStatement rollbackTransactionStatement;
  
  private static final String LOGIN_SQL = "SELECT username, password FROM Users WHERE username = ? AND password = ?";
  private PreparedStatement login;
  
  private static final String USER_SQL = "SELECT * FROM Users WHERE username = ?";
  private PreparedStatement user;
  
  private static final String INSERT_USERS_SQL = "INSERT INTO Users VALUES (?, ?, ?)";
  private PreparedStatement insertUser;
  
  private static final String DIRECT_SQL =
          "SELECT TOP (?) fid, day_of_month,carrier_id,flight_num,origin_city,dest_city,actual_time,capacity,price "
                  + "FROM Flights "
                  + "WHERE origin_city = ? AND dest_city = ? AND day_of_month = ? AND canceled = 0"
                  + "ORDER BY actual_time ASC, fid ASC";
  private static final String INDIRECT_SQL =
		  "SELECT TOP (?) f1.fid, f1.day_of_month,f1.carrier_id,f1.flight_num,"
		          + "f1.origin_city,f1.dest_city,f1.actual_time,f1.capacity, f1.price,"
		          + "f2.fid, f2.day_of_month,f2.carrier_id,f2.flight_num,f2.origin_city,f2.dest_city,"
		          + "f2.actual_time,f2.capacity,f2.price "
		                  + "FROM FLIGHTS f1, FLIGHTS f2 "
		                  + " WHERE f1.origin_city = ? AND f2.dest_city = ? AND f1.day_of_month = ? "
		                  + "AND f1.canceled = 0 AND f2.canceled = 0 AND f2.day_of_month = f1.day_of_month AND f1.dest_city = f2.origin_city "
		                  + " ORDER BY f1.actual_time + f2.actual_time, f1.fid, f2.fid ASC";
  private PreparedStatement directed;
  private PreparedStatement indirected;
  
  private static final String RESERVATION_CHECK_SQL = "SELECT count(*) as count FROM Reservations WHERE username = ? AND day = ? AND canceled = 0 ";
  private PreparedStatement checkDay;
  
  private static final String GET_SEAT1 = "SELECT COUNT(fid1) AS count FROM Reservations WHERE fid1 = ? AND canceled = 0";
  private static final String GET_SEAT2 = "SELECT COUNT(fid2) AS count FROM Reservations WHERE fid2 = ? AND canceled = 0";
  private PreparedStatement seat1;
  private PreparedStatement seat2;
  
  private static final String GET_RID = "SELECT nid FROM Id";
  private PreparedStatement rid;
  
  private static final String DELETEID = "DELETE FROM Id";
  private static final String UPDATEID = "INSERT INTO Id VALUES(?)";
  private PreparedStatement deleteId;
  private PreparedStatement updateId;
  
  private static final String UPDATE_RESERVATION = "INSERT INTO Reservations VALUES(?,?,?,?,?,?,?,?)";
  private PreparedStatement updateR;
  
  private static final String RESERVATION = "SELECT * FROM Reservations WHERE username = ? AND canceled = 0 ORDER BY rid";
  private PreparedStatement reservationList;
  
  private static final String FINDFLIGHT  = 
		  "SELECT fid, day_of_month, carrier_id, flight_num, origin_city, dest_city, actual_time, capacity, price "
  +"FROM Flights "
		  + "WHERE fid = ?";
  private PreparedStatement findFlight;
  
  private static final String PAYRES = "SELECT * FROM Reservations WHERE rid = ? AND username = ? AND canceled = 0 AND pay = 0";
  private PreparedStatement reservationToPay;
  
  private static final String BALANCEUP = "UPDATE Users Set balance = ? Where username = ?";
  private static final String PAYUP = "UPDATE Reservations Set pay = 1 Where rid = ?";
  private PreparedStatement balanceUp;
  private PreparedStatement payUp;
  
  private static final String RESERVATIONCANCEL = "SELECT * FROM Reservations WHERE username = ? AND canceled = 0 AND rid = ?";
  private PreparedStatement cancel;
  
  private static final String CANCELUP = "UPDATE Reservations set canceled = 1 WHERE rid = ?";
  private PreparedStatement cancelUp;
  
  private static final String REFUND = "UPDATE Users set balance = balance + ? WHERE username = ?";
  private PreparedStatement refund;
  
  private static final String CLEAR = "DELETE FROM Reservations; DELETE FROM Users; DELETE FROM Id;";
  private PreparedStatement clear;
  
  private static final String INITIALID = "INSERT INTO Id VALUES(1)";
  private PreparedStatement initial;
  class Flight
  {
    public int fid;
    public int dayOfMonth;
    public String carrierId;
    public String flightNum;
    public String originCity;
    public String destCity;
    public int time;
    public int capacity;
    public int price;

    @Override
    public String toString()
    {
      return "ID: " + fid + " Day: " + dayOfMonth + " Carrier: " + carrierId +
              " Number: " + flightNum + " Origin: " + originCity + " Dest: " + destCity + " Duration: " + time +
              " Capacity: " + capacity + " Price: " + price;
    }
  }

  class Itinerary implements Comparable<Itinerary>{
      public Flight f1;
      public Flight f2;
      public int dayOfMonth;
      public int duration;
      public int price;
      
      @Override
      public int compareTo(Itinerary it) {
          if (duration != it.duration) {
              return duration - it.duration;
          } else {
              return f1.fid - it.f1.fid;
          }
      }
      public String toString(int index) {
    	  int f = 1;
    	  if(f2 != null) {
    		  f = 2;
    	  }
    	  String title =  "Itinerary " + index + ": " + f + " flight(s)" + ", " + duration + " minutes\n";
    	  if(f2 == null) {
    		  return title + f1.toString() + "\n";
    	  } else {
    		  return title + f1.toString() + "\n" + f2.toString() + "\n";
    	  }
      }
     
  }
  
  public Query(String configFilename)
  {
    this.configFilename = configFilename;
  }

  /* Connection code to SQL Azure.  */
  public void openConnection() throws Exception
  {
    configProps.load(new FileInputStream(configFilename));

    jSQLDriver = configProps.getProperty("flightservice.jdbc_driver");
    jSQLUrl = configProps.getProperty("flightservice.url");
    jSQLUser = configProps.getProperty("flightservice.sqlazure_username");
    jSQLPassword = configProps.getProperty("flightservice.sqlazure_password");

    /* load jdbc drivers */
    Class.forName(jSQLDriver).newInstance();

    /* open connections to the flights database */
    conn = DriverManager.getConnection(jSQLUrl, // database
            jSQLUser, // user
            jSQLPassword); // password

    conn.setAutoCommit(true); //by default automatically commit after each statement

    /* You will also want to appropriately set the transaction's isolation level through:
       conn.setTransactionIsolation(...)
       See Connection class' JavaDoc for details.
    */
  }

  public void closeConnection() throws Exception
  {
    conn.close();
  }

  /**
   * Clear the data in any custom tables created. Do not drop any tables and do not
   * clear the flights table. You should clear any tables you use to store reservations
   * and reset the next reservation ID to be 1.
   */
  public void clearTables ()
  {
    // your code here
	  try {
		  prepareStatements();
		  clear.executeUpdate();
		  initial.executeUpdate();
	  }catch(Exception e) {}
  }

  /**
   * prepare all the SQL statements in this method.
   * "preparing" a statement is almost like compiling it.
   * Note that the parameters (with ?) are still not filled in
   */
  public void prepareStatements() throws Exception
  {
    beginTransactionStatement = conn.prepareStatement(BEGIN_TRANSACTION_SQL);
    commitTransactionStatement = conn.prepareStatement(COMMIT_SQL);
    rollbackTransactionStatement = conn.prepareStatement(ROLLBACK_SQL);

    checkFlightCapacityStatement = conn.prepareStatement(CHECK_FLIGHT_CAPACITY);
    clear = conn.prepareStatement(CLEAR);
    initial = conn.prepareStatement(INITIALID);
    login = conn.prepareStatement(LOGIN_SQL);
    user = conn.prepareStatement(USER_SQL);
    insertUser = conn.prepareStatement(INSERT_USERS_SQL);
    directed = conn.prepareStatement(DIRECT_SQL);
    indirected = conn.prepareStatement(INDIRECT_SQL);
    checkDay = conn.prepareStatement(RESERVATION_CHECK_SQL);
    seat1 = conn.prepareStatement(GET_SEAT1);
    seat2 = conn.prepareStatement(GET_SEAT2);
    rid = conn.prepareStatement(GET_RID);
    deleteId = conn.prepareStatement(DELETEID);
    updateId = conn.prepareStatement(UPDATEID);
    updateR = conn.prepareStatement(UPDATE_RESERVATION);
    reservationList = conn.prepareStatement(RESERVATION);
    findFlight = conn.prepareStatement(FINDFLIGHT);
    reservationToPay = conn.prepareStatement(PAYRES);
    balanceUp = conn.prepareStatement(BALANCEUP);
    payUp = conn.prepareStatement(PAYUP);
    cancel = conn.prepareStatement(RESERVATIONCANCEL);
    cancelUp = conn.prepareStatement(CANCELUP);
    refund = conn.prepareStatement(REFUND);
    /* add here more prepare statements for all the other queries you need */
    /* . . . . . . */
  }

  /**
   * Takes a user's username and password and attempts to log the user in.
   *
   * @param username
   * @param password
   *
   * @return If someone has already logged in, then return "User already logged in\n"
   * For all other errors, return "Login failed\n".
   *
   * Otherwise, return "Logged in as [username]\n".
   */
  public String transaction_login(String username, String password)
  {
	  if (this.username != null) {
		  return "User already logged in\n";
	  }
	  if (username.length() > 20 || password.length() > 20) {
			return "Login failed\n";
		}
	  try {
		  prepareStatements();
		  login.setString(1, username);
		  login.setString(2, password);
		  ResultSet result = login.executeQuery();
		  if(result.next()) {
			  this.username = username;
			  result.close();
			  return "Logged in as " + this.username + "\n";
			 } else {
				 throw new Exception();
			 }
		  } catch (Exception e){
			  return "Login failed\n";
		  }
  }

  /**
   * Implement the create user function.
   *
   * @param username new user's username. User names are unique the system.
   * @param password new user's password.
   * @param initAmount initial amount to deposit into the user's account, should be >= 0 (failure otherwise).
   *
   * @return either "Created user {@code username}\n" or "Failed to create user\n" if failed.
   */
  public String transaction_createCustomer (String username, String password, int initAmount)
  {
	  if (initAmount < 0 || username.length() > 20 || password.length() > 20 ) {
		  return "Failed to create user\n";
		  }
	  try {
		  beginTransaction();
		  prepareStatements();
		  user.setString(1, username);
		  ResultSet result = user.executeQuery();
		  if (result.next()) {
			  result.close();
			  throw new Exception();
		  } else {
			  insertUser.setString(1, username);
			  insertUser.setString(2, password);
			  insertUser.setInt(3, initAmount);
			  insertUser.executeUpdate();
			  commitTransaction();
			  return "Created user " + username + "\n";
		  }
	  }catch(Exception e) {
		  try {
			  rollbackTransaction();
		  } catch(Exception ex) {}
		  return "Failed to create user\n";
	  }
  }

  /**
   * Implement the search function.
   *
   * Searches for flights from the given origin city to the given destination
   * city, on the given day of the month. If {@code directFlight} is true, it only
   * searches for direct flights, otherwise is searches for direct flights
   * and flights with two "hops." Only searches for up to the number of
   * itineraries given by {@code numberOfItineraries}.
   *
   * The results are sorted based on total flight time.
   *
   * @param originCity
   * @param destinationCity
   * @param directFlight if true, then only search for direct flights, otherwise include indirect flights as well
   * @param dayOfMonth
   * @param numberOfItineraries number of itinitinerarieseraries to return
   *
   * @return If no itineraries were found, return "No flights match your selection\n".
   * If an error occurs, then return "Failed to search\n".
   *
   * Otherwise, the sorted itineraries printed in the following format:
   *
   * Itinerary [itinerary number]: [number of flights] flight(s), [total flight time] minutes\n
   * [first flight in itinerary]\n
   * ...
   * [last flight in itinerary]\n
   *
   * Each flight should be printed using the same format as in the {@code Flight} class. Itinerary numbers
   * in each search should always start from 0 and increase by 1.
 * @throws Exception 
   *
   * @see Flight#toString()
   */
  public String transaction_search(String originCity, String destinationCity, boolean directFlight, int dayOfMonth,
                                   int numberOfItineraries)
  {
	  StringBuffer sb = new StringBuffer();
	  int count = numberOfItineraries;
	  ArrayList<Itinerary> result = new ArrayList<Itinerary>(count);
	    try {
	    	beginTransaction();
	    	prepareStatements();
			directed.setInt(1, numberOfItineraries);
			directed.setString(2, originCity);
			directed.setString(3, destinationCity);
			directed.setInt(4, dayOfMonth);
			ResultSet directResult = directed.executeQuery();
			while(count > 0 && directResult.next()){
				count --;
				Itinerary it = new Itinerary();
				Flight f1 = new Flight();
				f1.fid = directResult.getInt("fid");
				f1.dayOfMonth = directResult.getInt("day_of_month");
		        f1.carrierId = directResult.getString("carrier_id");
		        f1.flightNum = directResult.getString("flight_num");
		        f1.originCity = directResult.getString("origin_city");
		        f1.destCity = directResult.getString("dest_city");
		        f1.time = directResult.getInt("actual_time");
		        f1.capacity = directResult.getInt("capacity");
		        f1.price = directResult.getInt("price");
		        it.f1 = f1;
		        it.duration = f1.time;
		        it.dayOfMonth = f1.dayOfMonth;
		        result.add(it);
			}
			directResult.close();
			indirected.clearParameters();
			indirected.setInt(1, count);
			indirected.setString(2, originCity);
			indirected.setString(3, destinationCity);
			indirected.setInt(4, dayOfMonth);
			ResultSet inDirectResult = indirected.executeQuery();
			
			while(count > 0 && !directFlight && inDirectResult.next()) {
				count --;
				Itinerary it = new Itinerary();
				Flight f1 = new Flight();
				f1.fid = inDirectResult.getInt(1);
				f1.dayOfMonth = inDirectResult.getInt(2);
		        f1.carrierId = inDirectResult.getString(3);
		        f1.flightNum = inDirectResult.getString(4);
		        f1.originCity = inDirectResult.getString(5);
		        f1.destCity = inDirectResult.getString(6);
		        f1.time = inDirectResult.getInt(7);
		        f1.capacity = inDirectResult.getInt(8);
		        f1.price = inDirectResult.getInt(9);
		        it.f1 = f1;
		        Flight f2 = new Flight();
		        f2.fid = inDirectResult.getInt(10);
				f2.dayOfMonth = inDirectResult.getInt(11);
		        f2.carrierId = inDirectResult.getString(12);
		        f2.flightNum = inDirectResult.getString(13);
		        f2.originCity = inDirectResult.getString(14);
		        f2.destCity = inDirectResult.getString(15);
		        f2.time = inDirectResult.getInt(16);
		        f2.capacity = inDirectResult.getInt(17);
		        f2.price = inDirectResult.getInt(18);
		        it.f2 = f2;
		        it.duration = f1.time + f2.time;
		        it.dayOfMonth = f1.dayOfMonth;
		        result.add(it);
			}
			inDirectResult.close();
			if(result.isEmpty()) {
				commitTransaction();
				return "No flights match your selection\n";
			}
			Collections.sort(result);
			for (int i = 0; i < result.size(); i ++) {
				sb.append(result.get(i).toString(i));
			}
			if(username != null) {
				this.itResult = result;
			}
			this.commitTransaction();
			return sb.toString();
	    } catch (Exception e) { 
	    	try {
	    		rollbackTransaction();
	    	}catch(Exception ex){}
	    	return "Failed to search\n";
	    }
  }

  /**
   * Same as {@code transaction_search} except that it only performs single hop search and
   * do it in an unsafe manner.
   *
   * @param originCity
   * @param destinationCity
   * @param directFlight
   * @param dayOfMonth
   * @param numberOfItineraries
   *
   * @return The search results. Note that this implementation *does not conform* to the format required by
   * {@code transaction_search}.
   */
  private String transaction_search_unsafe(String originCity, String destinationCity, boolean directFlight,
                                          int dayOfMonth, int numberOfItineraries)
  {
    StringBuffer sb = new StringBuffer();

    try
    {
      // one hop itineraries
      String unsafeSearchSQL =
              "SELECT TOP (" + numberOfItineraries + ") day_of_month,carrier_id,flight_num,origin_city,dest_city,actual_time,capacity,price "
                      + "FROM Flights "
                      + "WHERE origin_city = \'" + originCity + "\' AND dest_city = \'" + destinationCity + "\' AND day_of_month =  " + dayOfMonth + " "
                      + "ORDER BY actual_time ASC";

      Statement searchStatement = conn.createStatement();
      ResultSet oneHopResults = searchStatement.executeQuery(unsafeSearchSQL);

      while (oneHopResults.next())
      {
        int result_dayOfMonth = oneHopResults.getInt("day_of_month");
        String result_carrierId = oneHopResults.getString("carrier_id");
        String result_flightNum = oneHopResults.getString("flight_num");
        String result_originCity = oneHopResults.getString("origin_city");
        String result_destCity = oneHopResults.getString("dest_city");
        int result_time = oneHopResults.getInt("actual_time");
        int result_capacity = oneHopResults.getInt("capacity");
        int result_price = oneHopResults.getInt("price");

        sb.append("Day: " + result_dayOfMonth + " Carrier: " + result_carrierId + " Number: " + result_flightNum + " Origin: " + result_originCity + " Destination: " + result_destCity + " Duration: " + result_time + " Capacity: " + result_capacity + " Price: " + result_price + "\n");
      }
      oneHopResults.close();
    } catch (SQLException e) { e.printStackTrace(); }

    return sb.toString();
  }

  /**
   * Implements the book itinerary function.
   *
   * @param itineraryId ID of the itinerary to book. This must be one that is returned by search in the current session.
   *
   * @return If the user is not logged in, then return "Cannot book reservations, not logged in\n".
   * If try to book an itinerary with invalid ID, then return "No such itinerary {@code itineraryId}\n".
   * If the user already has a reservation on the same day as the one that they are trying to book now, then return
   * "You cannot book two flights in the same day\n".
   * For all other errors, return "Booking failed\n".
   *
   * And if booking succeeded, return "Booked flight(s), reservation ID: [reservationId]\n" where
   * reservationId is a unique number in the reservation system that starts from 1 and increments by 1 each time a
   * successful reservation is made by any user in the system.
   */
  public String transaction_book(int itineraryId)
  {
	  if(username == null) {
		  return "Cannot book reservations, not logged in\n";
	  }
	  if (itResult == null) {
		  return "Booking failed\n";
	  } 
	  if (!(itineraryId < itResult.size() && itineraryId >= 0)) {
		  return "No such itinerary " + itineraryId + "\n";
	  }
      Itinerary it = itResult.get(itineraryId);
      try {
    	  beginTransaction();
    	  //Check if same day
    	  int day = it.dayOfMonth;
    	  prepareStatements();
    	  checkDay.setString(1, this.username);
    	  checkDay.setInt(2, day);
    	  ResultSet check = checkDay.executeQuery();
    	  check.next();
    	  int checkIt = check.getInt("count");
    	  check.close();
    	  if(checkIt > 0) {
    		  rollbackTransaction();
    		  return "You cannot book two flights in the same day\n";
    	  }
    	  seat1.clearParameters();
		  seat2.clearParameters();
    	  seat1.setInt(1, it.f1.fid);
    	  seat2.setInt(1, it.f1.fid);
    	  ResultSet r1 = seat1.executeQuery();
    	  ResultSet r2 = seat2.executeQuery();
    	  r1.next();
    	  r2.next();
    	  int seatRemain = it.f1.capacity - r1.getInt("count") - r2.getInt("count");
    	  r1.close();
    	  r2.close();
    	  int seatRemain2 = 1;
    	  if (it.f2 != null) {
    		  seat1.clearParameters();
    		  seat2.clearParameters();
    		  seat1.setInt(1, it.f2.fid);
        	  seat2.setInt(1, it.f2.fid);
        	  ResultSet s1 = seat1.executeQuery();
        	  ResultSet s2 = seat2.executeQuery();
        	  s1.next();
        	  s2.next();
        	  seatRemain2 = it.f2.capacity - s1.getInt("count") - s2.getInt("count");
        	  s1.close();
        	  s2.close();
    	  }
    	  if (seatRemain <= 0 || seatRemain2 <= 0) {
    		  throw new Exception();
          }
    	  // All pass, do booking
    	  ResultSet id = rid.executeQuery();
    	  id.next();
    	  int rid = id.getInt("nid");
    	  id.close();
    	  int totalPrice = 0;
    	  if(it.f2 != null) {
    		  totalPrice = it.f1.price + it.f2.price;
    	  } else {
    		  totalPrice = it.f1.price;
    	  }
    	  deleteId.executeUpdate();
    	  updateId.setInt(1, rid + 1);
    	  updateId.executeUpdate();
    	  updateR.setInt(1, rid);
    	  updateR.setString(2, this.username);
    	  updateR.setInt(3, it.f1.fid);
    	  if(it.f2 != null) {
    		  updateR.setInt(4, it.f2.fid);  
    	  } else {
    		  updateR.setInt(4, -1);
    	  }
    	  updateR.setInt(5, 0);
    	  updateR.setInt(6, 0);
    	  updateR.setInt(7, it.dayOfMonth);
    	  updateR.setInt(8, totalPrice);
    	  updateR.executeUpdate();
    	  commitTransaction();
    	  return "Booked flight(s), reservation ID: " + rid + "\n";
    	  
      }catch(Exception e){
    	  try {
              rollbackTransaction();
          } catch (Exception ex) {};
    	  return "Booking failed\n";
      }  
  }

  /**
   * Implements the reservations function.
   *
   * @return If no user has logged in, then return "Cannot view reservations, not logged in\n"
   * If the user has no reservations, then return "No reservations found\n"
   * For all other errors, return "Failed to retrieve reservations\n"
   *
   * Otherwise return the reservations in the following format:
   *
   * Reservation [reservation ID] paid: [true or false]:\n"
   * [flight 1 under the reservation]
   * [flight 2 under the reservation]
   * Reservation [reservation ID] paid: [true or false]:\n"
   * [flight 1 under the reservation]
   * [flight 2 under the reservation]
   * ...
   *
   * Each flight should be printed using the same format as in the {@code Flight} class.
   *
   * @see Flight#toString()
   */
  public String transaction_reservations()
  {
	  if(this.username == null) {
		  return "Cannot view reservations, not logged in\n";
	  }
	  StringBuffer sb = new StringBuffer();
	  try {
		  beginTransaction();
		  prepareStatements();
		  reservationList.setString(1, this.username);
		  ResultSet r = reservationList.executeQuery();
		  boolean haveResult = false;
		  while(r.next()) {
			  haveResult = true;
			  int rid = r.getInt("rid");
			  int fid1 = r.getInt("fid1");
			  int fid2 = r.getInt("fid2");
			  int pay = r.getInt("pay");
			  String paid = "";
			  if(pay == 0) {
				  paid = "false";
			  } else {
				  paid = "true";
		  	  }
			  String title = "Reservation " + rid + " paid: " + paid + ":\n";
			  sb.append(title);
			  Flight f1 = new Flight();
			  findFlight.clearParameters();
			  findFlight.setInt(1, fid1);
			  ResultSet f = findFlight.executeQuery();
			  f.next();
			  f1.fid = f.getInt("fid");
			  f1.dayOfMonth = f.getInt("day_of_month");
		      f1.carrierId = f.getString("carrier_id");
		      f1.flightNum = f.getString("flight_num");
		      f1.originCity = f.getString("origin_city");
		      f1.destCity = f.getString("dest_city");
		      f1.time = f.getInt("actual_time");
		      f1.capacity = f.getInt("capacity");
		      f1.price = f.getInt("price");
		      String flight1 = f1.toString() + "\n";
		      sb.append(flight1);
		      f.close();
		      if(fid2 != -1) {
		    	  //
		    	  
		    	  Flight f2 = new Flight();
		    	  findFlight.clearParameters();
				  findFlight.setInt(1, fid2);
				  ResultSet flight = findFlight.executeQuery();
				  flight.next();
				  f2.fid = flight.getInt("fid");
				  f2.dayOfMonth = flight.getInt("day_of_month");
			      f2.carrierId = flight.getString("carrier_id");
			      f2.flightNum = flight.getString("flight_num");
			      f2.originCity = flight.getString("origin_city");
			      f2.destCity = flight.getString("dest_city");
			      f2.time = flight.getInt("actual_time");
			      f2.capacity = flight.getInt("capacity");
			      f2.price = flight.getInt("price");
			      String flight2 = f2.toString() + "\n";
			      sb.append(flight2);
		      }
		  }
		  commitTransaction();
		      if (haveResult) {
		    	  return sb.toString();
		      } else {
		    	  return "No reservations found\n";
		      }  
	  }catch(Exception e) {
		  try {
			  rollbackTransaction();
		  }catch(Exception b) {}
	  }
    return "Failed to retrieve reservations\n";
  }

  /**
   * Implements the cancel operation.
   *
   * @param reservationId the reservation ID to cancel
   *
   * @return If no user has logged in, then return "Cannot cancel reservations, not logged in\n"
   * For all other errors, return "Failed to cancel reservation [reservationId]"
   *
   * If successful, return "Canceled reservation [reservationId]"
   *
   * Even though a reservation has been canceled, its ID should not be reused by the system.
   */
  public String transaction_cancel(int reservationId)
  {
    // only implement this if you are interested in earning extra credit for the HW!
	  if (this.username == null) {
		  return "Cannot cancel reservations, not logged in\n";
	  }
	  try {
		  beginTransaction();
		  prepareStatements();
		  cancel.setString(1, this.username);
		  cancel.setInt(2, reservationId);
		  ResultSet c = cancel.executeQuery();
		  if(c.next()) {
			  int pay = c.getInt("pay");
			  if(pay == 1) {
				  int price = c.getInt("price");
				  refund.setInt(1, price);
				  refund.setString(2, this.username);
				  refund.executeUpdate();
			  }
			  c.close();
			  cancelUp.setInt(1, reservationId);
			  cancelUp.executeUpdate();
			  commitTransaction();
			  return "Canceled reservation " + reservationId + "\n";
		  }
		  throw new Exception();
	  }catch(Exception e) {
		  try {
			  rollbackTransaction();
		  }catch(Exception ex) {}
		    return "Failed to cancel reservation " + reservationId + "\n";
	  }
  }

  /**
   * Implements the pay function.
   *
   * @param reservationId the reservation to pay for.
   *
   * @return If no user has logged in, then return "Cannot pay, not logged in\n"
   * If the reservation is not found / not under the logged in user's name, then return
   * "Cannot find unpaid reservation [reservationId] under user: [username]\n"
   * If the user does not have enough money in their account, then return
   * "User has only [balance] in account but itinerary costs [cost]\n"
   * For all other errors, return "Failed to pay for reservation [reservationId]\n"
   *
   * If successful, return "Paid reservation: [reservationId] remaining balance: [balance]\n"
   * where [balance] is the remaining balance in the user's account.
   */
  public String transaction_pay (int reservationId)
  {
	  if(this.username == null) {
		  return "Cannot pay, not logged in\n";
	  }
	  try {
		  beginTransaction();
		  prepareStatements();
		  reservationToPay.setInt(1, reservationId);
		  reservationToPay.setString(2, this.username);;
		  ResultSet r = reservationToPay.executeQuery();
		  if(r.next()) {
			  int totalPrice = r.getInt("price");
			  r.close();
			  user.clearParameters();
			  user.setString(1, this.username);
			  ResultSet account = user.executeQuery();
			  account.next();
			  int balance = account.getInt("balance");
			  account.close();
			  if(totalPrice > balance) {
				  commitTransaction();
				  return "User has only " + balance + " in account but itinerary costs " + totalPrice +"\n";
			  } else {
				  balance = balance - totalPrice;
				  balanceUp.setInt(1,balance);
				  balanceUp.setString(2, this.username);
				  payUp.setInt(1, reservationId);
				  balanceUp.executeUpdate();
				  payUp.executeUpdate();
				  commitTransaction();
				  return "Paid reservation: " + reservationId + " remaining balance: " + balance + "\n";
			  }
		  }else {
			  commitTransaction();
			  return "Cannot find unpaid reservation " + reservationId + " under user: " + username + "\n";
		  }
	  }catch(Exception e) {
		  try {
			  rollbackTransaction();
		  } catch(Exception b) {}
		  return "Failed to pay for reservation " + reservationId + "\n";
	  }
  }

  /* some utility functions below */

  public void beginTransaction() throws SQLException
  {
    conn.setAutoCommit(false);
    beginTransactionStatement.executeUpdate();
  }

  public void commitTransaction() throws SQLException
  {
    commitTransactionStatement.executeUpdate();
    conn.setAutoCommit(true);
  }

  public void rollbackTransaction() throws SQLException
  {
    rollbackTransactionStatement.executeUpdate();
    conn.setAutoCommit(true);
  }

  /**
   * Shows an example of using PreparedStatements after setting arguments. You don't need to
   * use this method if you don't want to.
   */
  private int checkFlightCapacity(int fid) throws SQLException
  {
    checkFlightCapacityStatement.clearParameters();
    checkFlightCapacityStatement.setInt(1, fid);
    ResultSet results = checkFlightCapacityStatement.executeQuery();
    results.next();
    int capacity = results.getInt("capacity");
    results.close();

    return capacity;
  }
}
