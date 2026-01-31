#pragma once

#include "protocol.hpp"
#include <string>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#endif

/**
 * UDP Client for the Distributed Banking System
 * 
 * Features:
 * - At-least-once and At-most-once invocation semantics
 * - Configurable timeout and retry
 * - Full banking operations support
 */
class Client {
public:
    /**
     * Constructor
     * @param serverIp Server IP address
     * @param serverPort Server port number
     * @param atMostOnce Use at-most-once semantics if true, at-least-once if false
     * @param timeoutMs Timeout in milliseconds
     * @param retryCount Number of retries on timeout
     */
    Client(const std::string& serverIp, int serverPort, bool atMostOnce, int timeoutMs, int retryCount);
    
    /**
     * Destructor - cleanup socket
     */
    ~Client();

    /**
     * Initialize the client (create socket, etc.)
     * @return true on success
     */
    bool init();

    /**
     * Run the interactive client menu
     */
    void run();

private:
    // Network configuration
    std::string serverIp_;
    int serverPort_;
    bool atMostOnce_;
    int timeoutMs_;
    int retryCount_;

#ifdef _WIN32
    SOCKET sock_;
#else
    int sock_;
#endif
    sockaddr_in serverAddr_;

    // Request ID counter
    uint64_t nextRequestId_;

    /**
     * Send a request and wait for reply
     * @param opCode Operation code
     * @param body Request body
     * @param reply Output reply message
     * @return true on success
     */
    bool call(uint16_t opCode, const std::vector<uint8_t>& body, proto::Message& reply);

    // Operation handlers
    void handleOpen();
    void handleClose();
    void handleDeposit();
    void handleWithdraw();
    void handleQueryBalance();
    void handleTransfer();
    void handleMonitor();

    // Utility functions
    void clearScreen();
    std::string readLine(const std::string& prompt);
    std::string readPassword(const std::string& prompt);
    bool readInt(const std::string& prompt, int& out);
    bool readDouble(const std::string& prompt, double& out);
    uint16_t readCurrency();
};
