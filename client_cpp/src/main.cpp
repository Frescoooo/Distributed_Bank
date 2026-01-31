#include "client.hpp"
#include <iostream>
#include <cstring>

/**
 * Main entry point for the C++ Banking Client
 * 
 * Usage:
 *   client.exe --server 127.0.0.1 --port 9000 --sem atmost --timeout 500 --retry 5
 * 
 * Arguments:
 *   --server   Server IP address (default: 127.0.0.1)
 *   --port     Server port number (default: 9000)
 *   --sem      Invocation semantics: "atmost" or "atleast" (default: atmost)
 *   --timeout  Timeout in milliseconds (default: 500)
 *   --retry    Number of retries (default: 5)
 */
int main(int argc, char* argv[]) {
    std::string server = "127.0.0.1";
    int port = 9000;
    std::string sem = "atmost";
    int timeout = 500;
    int retry = 5;

    // Parse command line arguments
    for (int i = 1; i < argc; i++) {
        if (std::strcmp(argv[i], "--server") == 0 && i + 1 < argc) {
            server = argv[++i];
        } else if (std::strcmp(argv[i], "--port") == 0 && i + 1 < argc) {
            port = std::atoi(argv[++i]);
        } else if (std::strcmp(argv[i], "--sem") == 0 && i + 1 < argc) {
            sem = argv[++i];
        } else if (std::strcmp(argv[i], "--timeout") == 0 && i + 1 < argc) {
            timeout = std::atoi(argv[++i]);
        } else if (std::strcmp(argv[i], "--retry") == 0 && i + 1 < argc) {
            retry = std::atoi(argv[++i]);
        } else if (std::strcmp(argv[i], "--help") == 0 || std::strcmp(argv[i], "-h") == 0) {
            std::cout << "Usage: " << argv[0] << " [options]\n";
            std::cout << "Options:\n";
            std::cout << "  --server <ip>     Server IP address (default: 127.0.0.1)\n";
            std::cout << "  --port <port>     Server port (default: 9000)\n";
            std::cout << "  --sem <semantic>  atmost or atleast (default: atmost)\n";
            std::cout << "  --timeout <ms>    Timeout in milliseconds (default: 500)\n";
            std::cout << "  --retry <count>   Retry count (default: 5)\n";
            return 0;
        }
    }

    bool atMostOnce = (sem == "atmost" || sem == "at-most-once");

    std::cout << "========================================\n";
    std::cout << "   Distributed Banking System - C++ Client\n";
    std::cout << "========================================\n\n";

    Client client(server, port, atMostOnce, timeout, retry);

    if (!client.init()) {
        std::cerr << "Failed to initialize client\n";
        return 1;
    }

    client.run();

    return 0;
}
